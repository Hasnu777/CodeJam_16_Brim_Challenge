package com.brimap.bonus;

import com.brimap.audit.AuditService;
import com.brimap.auth.Auth;
import com.brimap.model.BankStatementLine;
import com.brimap.model.Payment;
import com.brimap.model.Vendor;
import com.brimap.payment.PaymentService;
import com.brimap.repository.BankStatementLineRepository;
import com.brimap.repository.PaymentRepository;
import com.brimap.repository.ReconResolutionRepository;
import com.brimap.model.ReconResolution;
import com.brimap.config.AppClock;
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
    private final ReconResolutionRepository resolutions;
    private final AppClock clock;

    public record Match(String bankTxnId, String postedDate, String description, BigDecimal amount, String paymentId, String invoiceId,
                        String vendorName, String status, String explanation) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('FM','ADM','AUD')")
    @Transactional(readOnly = true)
    public Map<String, Object> reconcile() {
        // Sorted: findAll() returns rows in storage order, which changes when a payment is updated (D-105).
        List<Payment> pays = payments.findAll().stream().filter(p -> !"CARD".equals(p.getMethod()))
                .sorted(Comparator.comparing(Payment::getId)).toList();
        Map<String, String> names = vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, Vendor::getLegalName));
        Set<String> used = new HashSet<>();
        List<Match> matched = new ArrayList<>(), unmatchedLines = new ArrayList<>();
        List<BankStatementLine> leftovers = new ArrayList<>();
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
                if (hit == null) hit = nearestCheque(pays, amt, l.getPostedDate(), used).orElse(null);
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
            leftovers.add(l);
        }
        // Second pass, after every exact match: a cheque that cleared for a few cents more or less than it was written for
        // (the dataset's September rent: written $6,990.48, cleared $6,990.53). It is not "unknown money": it is that
        // cheque with the wrong amount, a bank encoding error or an altered cheque, and the bank owes us the difference.
        for (BankStatementLine l : leftovers) {
            BigDecimal amt = l.getDebit() != null ? l.getDebit() : l.getCredit() == null ? BigDecimal.ZERO : l.getCredit().negate();
            String d = l.getDescription() == null ? "" : l.getDescription().trim();
            Payment near = CHEQUE.matcher(d).find() ? nearestCheque(pays, amt, l.getPostedDate(), used, CHEQUE_TOLERANCE).orElse(null) : null;
            if (near != null) {
                used.add(near.getId());
                BigDecimal diff = amt.subtract(near.getAmount());
                unmatchedLines.add(new Match(l.getId(), l.getPostedDate().toString(), d, amt, near.getId(), near.getInvoiceId(), names.get(near.getVendorId()),
                        "AMOUNT_DIFFERS", "Likely " + near.getId() + ", the cheque written " + written(near) + " for " + near.getAmount().toPlainString()
                        + ", but it cleared for " + amt.toPlainString() + " (" + (diff.signum() > 0 ? "$" + diff.toPlainString() + " more" : "$" + diff.negate().toPlainString() + " less")
                        + "). Bank encoding error or altered cheque: ask the bank for the cheque image and claim the difference"));
                continue;
            }
            unmatchedLines.add(new Match(l.getId(), l.getPostedDate().toString(), d, amt, null, null, null, "UNMATCHED", explain(l, amt, pays, used)));
        }
        List<Match> unmatchedPayments = new ArrayList<>();
        java.time.LocalDate statementEnd = statement.findAll().stream().map(BankStatementLine::getPostedDate).max(Comparator.naturalOrder()).orElse(null);
        for (Payment p : pays) {
            if (used.contains(p.getId()) || !("SETTLED".equals(p.getStatus()) || "SENT".equals(p.getStatus()))) continue;
            java.time.LocalDate wrote = written(p);
            long waiting = wrote == null || statementEnd == null ? 0 : ChronoUnit.DAYS.between(wrote, statementEnd);
            String why = "SENT".equals(p.getStatus())
                    ? ("CHEQUE".equals(p.getMethod()) && waiting > 30
                        ? "Outstanding cheque: written " + wrote + ", still not cashed " + waiting + " days later. Confirm with the vendor it arrived; reissue if lost"
                        : "In transit: sent but not on the statement yet (statement ends " + (statementEnd == null ? "?" : statementEnd) + ")")
                    : p.isHistorical() ? "Marked settled but no statement debit found: check the bank portal" : "Settled in this app (simulated EFT) after the statement period";
            unmatchedPayments.add(new Match(null, p.getPaidDate() == null ? null : p.getPaidDate().toString(), p.getReference(), p.getAmount(), p.getId(),
                    p.getInvoiceId(), names.get(p.getVendorId()), p.getStatus(), why));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("matched", matched);
        m.put("unmatchedStatementLines", unmatchedLines);
        m.put("unmatchedPayments", unmatchedPayments);
        // D-109: how each leftover was explained, keyed "LINE:<bank txn>" / "PAYMENT:<payment id>", and the choices.
        Map<String, ReconResolution> res = new LinkedHashMap<>();
        resolutions.findAll().forEach(r -> res.put(r.getItemKey(), r));
        m.put("resolutions", res);
        m.put("options", ReconOptions.ALL);
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

    /**
     * Cheque numbers aren't in the dataset's payment references, so a "CHEQUE n CLEARED" line is matched by amount. A
     * cheque clears on or after the day it was written (paid date, else scheduled date) and within 45 days. Recurring
     * bills (the $6,990.48 monthly rent) give several candidates: take the shortest wait, ties by id, never the first in
     * database order (D-105).
     */
    static Optional<Payment> nearestCheque(List<Payment> pays, BigDecimal amt, java.time.LocalDate posted, Set<String> used) {
        return nearestCheque(pays, amt, posted, used, BigDecimal.ZERO);
    }

    /** A cleared amount this close to the written amount is the same cheque with an amount error (D-109). */
    static final BigDecimal CHEQUE_TOLERANCE = new BigDecimal("1.00");

    /** As above, allowing the cleared amount to differ by up to {@code tolerance}; the closest amount wins, then the shortest wait. */
    static Optional<Payment> nearestCheque(List<Payment> pays, BigDecimal amt, java.time.LocalDate posted, Set<String> used, BigDecimal tolerance) {
        return pays.stream().filter(p -> "CHEQUE".equals(p.getMethod()) && !used.contains(p.getId()) && p.getAmount() != null
                        && p.getAmount().subtract(amt).abs().compareTo(tolerance) <= 0 && written(p) != null)
                .filter(p -> { long wait = ChronoUnit.DAYS.between(written(p), posted); return wait >= 0 && wait <= 45; })
                .min(Comparator.<Payment, BigDecimal>comparing(p -> p.getAmount().subtract(amt).abs())
                        .thenComparingLong(p -> ChronoUnit.DAYS.between(written(p), posted)).thenComparing(Payment::getId));
    }

    private static java.time.LocalDate written(Payment p) { return p.getPaidDate() != null ? p.getPaidDate() : p.getScheduledDate(); }

    public record ResolveRequest(String kind, List<String> itemIds, String resolution, String note) {}

    /**
     * Record how leftovers were explained (D-109). Only current leftovers can be resolved; resolution null reopens them.
     * Each item gets its own audit row, so an item's story (investigating, then disputed) is in the audit log.
     */
    @PostMapping("/resolve")
    @PreAuthorize("hasAnyRole('FM','ADM')")
    @Transactional
    public Map<String, Object> resolve(@RequestBody ResolveRequest r) {
        ReconOptions.Kind kind;
        try { kind = ReconOptions.Kind.valueOf(String.valueOf(r.kind())); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("kind must be LINE or PAYMENT"); }
        if (r.itemIds() == null || r.itemIds().isEmpty()) throw new IllegalArgumentException("Pick at least one item");
        ReconOptions.Option opt = r.resolution() == null ? null : ReconOptions.find(kind, r.resolution())
                .orElseThrow(() -> new IllegalArgumentException("Unknown resolution " + r.resolution() + " for " + kind));
        String note = r.note() == null ? null : r.note().trim();
        if (opt != null && opt.noteRequired() && (note == null || note.isEmpty())) throw new IllegalArgumentException("Add a note: " + opt.label());
        @SuppressWarnings("unchecked")
        List<Match> open = (List<Match>) reconcile().get(kind == ReconOptions.Kind.LINE ? "unmatchedStatementLines" : "unmatchedPayments");
        Map<String, Match> byId = new HashMap<>();
        open.forEach(x -> byId.put(kind == ReconOptions.Kind.LINE ? x.bankTxnId() : x.paymentId(), x));
        String actor = Auth.actorId();
        List<String> done = new ArrayList<>();
        for (String id : r.itemIds()) {
            Match item = byId.get(id);
            if (item == null) throw new IllegalArgumentException(id + " is not an open reconciliation item");
            String key = kind + ":" + id;
            ReconResolution prior = resolutions.findById(key).orElse(null);
            Map<String, Object> before = prior == null ? null : Map.of("resolution", prior.getResolution(), "note", String.valueOf(prior.getNote()));
            if (opt == null) {
                if (prior == null) continue;
                resolutions.delete(prior);
                audit.record("RECON_ITEM_REOPENED", "reconciliation", key, before, null, item.description() + " " + item.amount() + ": reopened");
            } else {
                ReconResolution x = prior == null ? new ReconResolution() : prior;
                x.setItemKey(key); x.setKind(kind.name()); x.setItemId(id); x.setResolution(opt.code()); x.setNote(note);
                x.setResolvedBy(actor); x.setResolvedAt(clock.now());
                resolutions.save(x);
                audit.record("RECON_ITEM_RESOLVED", "reconciliation", key, before, Map.of("resolution", opt.code(), "note", String.valueOf(note)),
                        item.description() + " " + item.amount() + ": " + opt.label() + (note == null || note.isEmpty() ? "" : " (" + note + ")")
                                + ("external".equals(opt.scope()) ? ". Recorded only: " + opt.outOfScope() : ""));
            }
            done.add(id);
        }
        return Map.of("resolved", done, "by", actor);
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
