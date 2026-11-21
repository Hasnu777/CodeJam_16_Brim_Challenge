package com.brimap.bonus;

import com.brimap.config.AppClock;
import com.brimap.model.*;
import com.brimap.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bonus 1 - fraud alerts in one place: the payment-fraud flags (bank details, lookalikes, card double-payment),
 * unverified bank changes, and money about to go to a vendor whose bank details changed recently. Built entirely
 * from data the rules already computed: no new judgement, just the view an AP lead would want every morning.
 */
@RestController
@RequestMapping("/api/fraud")
@RequiredArgsConstructor
public class FraudService {
    static final Set<String> FRAUD_CODES = Set.of("B01", "B02", "B03", "B04", "V04", "V05", "V06", "D04");
    static final int RECENT_DAYS = 30;

    private final FlagRepository flags;
    private final InvoiceRepository invoices;
    private final VendorRepository vendors;
    private final BankChangeRequestRepository changes;
    private final PaymentRepository payments;
    private final AppClock clock;

    public record Alert(String kind, String severity, String title, String message, String invoiceId, String vendorId, String vendorName,
                        BigDecimal amountCad, String ref) {}

    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('CLK','FM','ADM','AUD')")
    @Transactional(readOnly = true)
    public Map<String, Object> alerts() {
        Map<String, Invoice> inv = invoices.findAll().stream().collect(Collectors.toMap(Invoice::getId, i -> i));
        Map<String, String> names = vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, Vendor::getLegalName));
        List<Alert> out = new ArrayList<>();
        for (Flag f : flags.findAll()) {
            if (!f.isOpen() || !FRAUD_CODES.contains(f.getRuleCode())) continue;
            Invoice i = inv.get(f.getInvoiceId());
            String vid = i == null ? null : i.getVendorId();
            out.add(new Alert("FLAG", f.getSeverity().name(), f.getRuleCode() + " " + f.getTitle(), f.getMessage(), f.getInvoiceId(), vid,
                    vid == null ? (i == null ? null : i.getVendorNameRaw()) : names.get(vid), i == null ? null : i.getTotalCad(), f.getRuleCode()));
        }
        for (BankChangeRequest c : changes.findByStatus("PENDING")) {
            BigDecimal exposed = payments.findAll().stream().filter(p -> c.getVendorId().equals(p.getVendorId()) && "SCHEDULED".equals(p.getStatus()))
                    .map(p -> p.getAmountCad() == null ? BigDecimal.ZERO : p.getAmountCad()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal pendingBills = inv.values().stream().filter(i -> c.getVendorId().equals(i.getVendorId()) && !i.isHistorical()
                            && i.getStatus() != InvoiceStatus.PAID && i.getStatus() != InvoiceStatus.REJECTED && i.getTotalCad() != null)
                    .map(Invoice::getTotalCad).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new Alert("BANK_CHANGE", "BLOCK", "Unverified bank change " + c.getId(),
                    names.get(c.getVendorId()) + ": ..." + c.getOldAccountLast4() + " -> ..." + c.getNewAccountLast4() + " on " + c.getChangedAt()
                            + " via " + c.getRequestChannel().replace('_', ' ') + "; " + pendingBills.toPlainString() + " CAD of open bills would pay to it",
                    null, c.getVendorId(), names.get(c.getVendorId()), pendingBills.add(exposed), c.getId()));
        }
        // Money scheduled to a vendor whose (verified) bank details changed in the last 30 days: verified, but worth a second look.
        for (Payment p : payments.findByStatus("SCHEDULED")) {
            changes.findByVendorIdOrderByChangedAtDesc(p.getVendorId() == null ? "-" : p.getVendorId()).stream().findFirst()
                    .filter(c -> !"PENDING".equals(c.getStatus()) && ChronoUnit.DAYS.between(c.getChangedAt(), clock.today()) <= RECENT_DAYS)
                    .ifPresent(c -> out.add(new Alert("RECENT_CHANGE_PAYMENT", "WARN", "Payment shortly after a bank change",
                            p.getId() + " (" + p.getAmountCad().toPlainString() + " CAD) goes to an account changed " + ChronoUnit.DAYS.between(c.getChangedAt(), clock.today())
                                    + " days ago (" + c.getId() + ", " + c.getStatus().toLowerCase() + ")", p.getInvoiceId(), p.getVendorId(), names.get(p.getVendorId()),
                            p.getAmountCad(), c.getId())));
        }
        out.sort(Comparator.comparing((Alert a) -> a.severity()).thenComparing(a -> a.amountCad() == null ? BigDecimal.ZERO : a.amountCad(), Comparator.reverseOrder()));
        BigDecimal atRisk = out.stream().filter(a -> "BLOCK".equals(a.severity()) && a.invoiceId() != null)
                .collect(Collectors.toMap(Alert::invoiceId, a -> a.amountCad() == null ? BigDecimal.ZERO : a.amountCad(), (a, b) -> a))
                .values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alerts", out);
        m.put("blockedAmountCad", atRisk);
        m.put("countsByCode", out.stream().collect(Collectors.groupingBy(Alert::ref, TreeMap::new, Collectors.counting())));
        return m;
    }
}
