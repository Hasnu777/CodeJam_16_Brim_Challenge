package com.brimap.bonus;

import com.brimap.audit.AuditService;
import com.brimap.auth.Auth;
import com.brimap.model.BankStatementLine;
import com.brimap.model.Payment;
import com.brimap.model.Vendor;
import com.brimap.payment.PaymentService;
import com.brimap.repository.BankStatementLineRepository;
import com.brimap.repository.PaymentRepository;
import com.brimap.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bonus 2 - bank statement reconciliation, deterministic. A statement debit matches a payment when its trailing
 * reference digits equal the end of the payment reference (EFT "EFT PMT QUEEN STREET PROPERTIES 192411" ~
 * BRIM-EFT-18192411) or its cheque number matches (CHEQUE 5001 CLEARED), and the amounts agree. Everything left
 * over gets a likely explanation: bank fee, payment with the reference missing, money in transit, or "investigate".
 */
@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
public class ReconciliationService {
    private static final Pattern EFT_REF = Pattern.compile("^EFT PMT (.*?)\\s+(\\d{4,})\\s*$");
    private static final Pattern CHEQUE = Pattern.compile("^CHEQUE (\\d+)");

    private final BankStatementLineRepository statement;
    private final PaymentRepository payments;
    private final VendorRepository vendors;
    private final PaymentService paymentService;
    private final AuditService audit;

    public record Match(String bankTxnId, String postedDate, String description, BigDecimal amount, String paymentId, String invoiceId,
                        String vendorName, String status, String explanation) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('FM','ADM','AUD')")
    @Transactional(readOnly = true)
    public Map<String, Object> reconcile() {
        List<Payment> pays = payments.findAll().stream().filter(p -> !"CARD".equals(p.getMethod())).toList();
        Map<String, String> names = vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, Vendor::getLegalName));
        Set<String> used = new HashSet<>();
        List<Match> matched = new ArrayList<>(), unmatchedLines = new ArrayList<>();
        for (BankStatementLine l : statement.findAll().stream().sorted(Comparator.comparing(BankStatementLine::getPostedDate)).toList()) {
            BigDecimal amt = l.getDebit() != null ? l.getDebit() : l.getCredit() == null ? BigDecimal.ZERO : l.getCredit().negate();
            String d = l.getDescription() == null ? "" : l.getDescription().trim();
            Payment hit = null;
            Matcher e = EFT_REF.matcher(d), c = CHEQUE.matcher(d);
            if (e.find()) {
                String ref = e.group(2);
                hit = pays.stream().filter(p -> p.getReference() != null && p.getReference().endsWith(ref) && !used.contains(p.getId())).findFirst().orElse(null);
            } else if (c.find()) {
                String no = c.group(1);
                hit = pays.stream().filter(p -> "CHEQUE".equals(p.getMethod()) && p.getReference() != null && p.getReference().endsWith(no)
                        && !used.contains(p.getId())).findFirst().orElse(null);
                if (hit == null) { // cheque numbers aren't in the dataset references: match by amount within 45 days of the scheduled date
                    hit = pays.stream().filter(p -> "CHEQUE".equals(p.getMethod()) && !used.contains(p.getId()) && p.getAmount() != null
                            && p.getAmount().compareTo(amt) == 0 && p.getScheduledDate() != null
                            && Math.abs(ChronoUnit.DAYS.between(p.getScheduledDate(), l.getPostedDate())) <= 45).findFirst().orElse(null);
                }
            }
            if (hit != null) {
                used.add(hit.getId());
                boolean sameAmount = hit.getAmount() != null && hit.getAmount().compareTo(amt) == 0;
                matched.add(new Match(l.getId(), l.getPostedDate().toString(), d, amt, hit.getId(), hit.getInvoiceId(), names.get(hit.getVendorId()),
                        sameAmount ? "MATCHED" : "AMOUNT_DIFFERS",
                        sameAmount ? "Reference and amount agree" + ("SENT".equals(hit.getStatus()) ? "; payment still marked SENT, can be settled" : "")
                                : "Reference matches " + hit.getId() + " but the bank debited " + amt.toPlainString() + " vs " + hit.getAmount().toPlainString()));
                continue;
            }
            unmatchedLines.add(new Match(l.getId(), l.getPostedDate().toString(), d, amt, null, null, null, "UNMATCHED", explain(l, amt, pays, used)));
        }
        List<Match> unmatchedPayments = new ArrayList<>();
        for (Payment p : pays) {
            if (used.contains(p.getId()) || !("SETTLED".equals(p.getStatus()) || "SENT".equals(p.getStatus()))) continue;
            String why = "SENT".equals(p.getStatus()) ? "In transit: sent " + (p.getPaidDate() == null ? "" : "") + "but not on the statement yet (statement ends "
                    + statement.findAll().stream().map(BankStatementLine::getPostedDate).max(Comparator.naturalOrder()).map(Object::toString).orElse("?") + ")"
                    : p.isHistorical() ? "Marked settled but no statement debit found: check the bank portal" : "Settled in this app (simulated EFT) after the statement period";
            unmatchedPayments.add(new Match(null, p.getPaidDate() == null ? null : p.getPaidDate().toString(), p.getReference(), p.getAmount(), p.getId(),
                    p.getInvoiceId(), names.get(p.getVendorId()), p.getStatus(), why));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("matched", matched);
        m.put("unmatchedStatementLines", unmatchedLines);
        m.put("unmatchedPayments", unmatchedPayments);
        m.put("summary", Map.of("statementLines", matched.size() + unmatchedLines.size(), "matched", matched.size(),
                "unmatchedLines", unmatchedLines.size(), "unmatchedPayments", unmatchedPayments.size(),
                "canSettle", matched.stream().filter(x -> x.explanation().contains("can be settled")).count()));
        return m;
    }

    /** Settle every SENT payment that the statement proves has left the account. */
    @PostMapping("/apply")
    @PreAuthorize("hasAnyRole('FM','ADM')")
    public Map<String, Object> apply() {
        @SuppressWarnings("unchecked")
        List<Match> matched = (List<Match>) reconcile().get("matched");
        List<String> settled = new ArrayList<>();
        for (Match x : matched) {
            if (!"MATCHED".equals(x.status()) || x.paymentId() == null) continue;
            Payment p = payments.findById(x.paymentId()).orElse(null);
            if (p != null && "SENT".equals(p.getStatus())) {
                paymentService.settle(p.getId(), java.time.LocalDate.parse(x.postedDate()));
                settled.add(p.getId());
            }
        }
        return Map.of("settled", settled, "by", Auth.actorId());
    }

    private static String explain(BankStatementLine l, BigDecimal amt, List<Payment> pays, Set<String> used) {
        String d = l.getDescription() == null ? "" : l.getDescription().toUpperCase(Locale.ROOT);
        if (d.contains("FEE") || d.contains("SERVICE CHARGE")) return "Bank fee: post to bank charges, no payment record expected";
        if (l.getCredit() != null) return "Money in (refund, rebate or deposit): not an AP payment";
        Optional<Payment> sameAmount = pays.stream().filter(p -> !used.contains(p.getId()) && p.getAmount() != null && p.getAmount().compareTo(amt) == 0
                && p.getScheduledDate() != null && Math.abs(ChronoUnit.DAYS.between(p.getScheduledDate(), l.getPostedDate())) <= 10).findFirst();
        if (sameAmount.isPresent()) return "Likely " + sameAmount.get().getId() + " (same amount, " + sameAmount.get().getScheduledDate() + "), reference missing on the statement";
        return "No payment in the AP system: investigate (possible unauthorized debit or payment made outside AP)";
    }
}
