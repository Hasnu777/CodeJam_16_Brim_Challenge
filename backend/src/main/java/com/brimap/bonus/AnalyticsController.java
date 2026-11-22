package com.brimap.bonus;

import com.brimap.auth.Auth;
import com.brimap.auth.CurrentUser;
import com.brimap.model.Payment;
import com.brimap.model.Role;
import com.brimap.model.Vendor;
import com.brimap.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bonus 4 - spend analytics by vendor, vendor category, department or GL account over a date range (CAD).
 * The arithmetic lives in {@link SpendAggregator}; this class only loads data and enforces scope:
 * approvers see their own department only (enforced here, not in the UI).
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final VendorRepository vendors;
    private final DepartmentRepository departments;
    private final GlAccountRepository gl;
    private final PaymentRepository payments;

    @GetMapping("/spend")
    @PreAuthorize("hasAnyRole('FM','ADM','AUD','APR')")
    @Transactional(readOnly = true)
    public SpendAggregator.Result spend(@RequestParam(defaultValue = "vendor") String groupBy, @RequestParam(required = false) LocalDate from,
                                        @RequestParam(required = false) LocalDate to) {
        CurrentUser u = Auth.require();
        SpendAggregator.GroupBy g = SpendAggregator.GroupBy.parse(groupBy);
        String deptScope = u.role() == Role.APR ? u.departmentId() : null;

        Map<String, String> labels = new HashMap<>();
        Map<String, String> vendorCategory = new HashMap<>();
        for (Vendor v : vendors.findAll()) {
            vendorCategory.put(v.getId(), v.getCategory());
            if (g == SpendAggregator.GroupBy.VENDOR) labels.put(v.getId(), v.getLegalName());
        }
        if (g == SpendAggregator.GroupBy.DEPT) departments.findAll().forEach(d -> labels.put(d.getId(), d.getName()));
        if (g == SpendAggregator.GroupBy.GL) gl.findAll().forEach(a -> labels.put(a.getAccountNumber(), a.getName()));

        return SpendAggregator.aggregate(g, invoices.findAll(), g.lineLevel() ? lines.findAll() : List.of(),
                new SpendAggregator.Reference(labels, vendorCategory), from, to, deptScope);
    }

    public record VendorOpportunity(String vendorId, String vendorName, int payments, BigDecimal amountCad, BigDecimal benefitCad) {}
    public record CardOpportunity(CardPaymentAdvisor.Terms terms, CardPaymentAdvisor.Summary scheduledSwitchable,
                                  CardPaymentAdvisor.Summary paidByEftCouldHaveBeenCard, CardPaymentAdvisor.Summary paidByCard,
                                  int cardAcceptingVendors, List<VendorOpportunity> byVendor) {}

    /**
     * Bonus 5 - Brim card opportunity. Replays every settled payment through {@link CardPaymentAdvisor}: what EFT/cheque
     * payments to card-accepting vendors would have earned on the Brim AP card, what card payments did earn, and what
     * the scheduled queue can still switch.
     */
    @GetMapping("/card-opportunity")
    @PreAuthorize("hasAnyRole('CLK','FM','ADM','AUD')")
    @Transactional(readOnly = true)
    public CardOpportunity cardOpportunity() {
        var terms = CardPaymentAdvisor.Terms.DEFAULT;
        Map<String, Vendor> vById = vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, v -> v));
        List<CardPaymentAdvisor.Advice> sched = new ArrayList<>(), missed = new ArrayList<>(), onCard = new ArrayList<>();
        List<BigDecimal> schedAmt = new ArrayList<>(), missedAmt = new ArrayList<>(), onCardAmt = new ArrayList<>();
        Map<String, List<Object[]>> perVendor = new TreeMap<>();
        for (Payment p : payments.findAll()) {
            Vendor v = vById.get(p.getVendorId());
            boolean accepts = v != null && v.isAcceptsCardPayment();
            if ("SCHEDULED".equals(p.getStatus())) {
                var a = CardPaymentAdvisor.advise(new CardPaymentAdvisor.Input(accepts, p.getMethod(), p.getCurrency(), p.getAmountCad(),
                        p.getScheduledDate(), p.getDiscountTaken()), terms);
                if (a.verdict() == CardPaymentAdvisor.Verdict.SWITCH) { sched.add(a); schedAmt.add(p.getAmountCad()); }
            } else if ("SETTLED".equals(p.getStatus()) && accepts) {
                LocalDate charged = p.getPaidDate() != null ? p.getPaidDate() : p.getScheduledDate();
                boolean card = "CARD".equals(p.getMethod());
                var a = CardPaymentAdvisor.advise(new CardPaymentAdvisor.Input(true, card ? "CARD" : "EFT", p.getCurrency(), p.getAmountCad(),
                        charged, p.getDiscountTaken()), terms);
                if (card) { onCard.add(a); onCardAmt.add(p.getAmountCad()); }
                else if (a.benefits()) {
                    missed.add(a); missedAmt.add(p.getAmountCad());
                    perVendor.computeIfAbsent(v.getId(), k -> new ArrayList<>()).add(new Object[]{p.getAmountCad(), a.benefitCad()});
                }
            }
        }
        List<VendorOpportunity> rows = perVendor.entrySet().stream().map(e -> {
            BigDecimal amt = e.getValue().stream().map(o -> (BigDecimal) o[0]).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ben = e.getValue().stream().map(o -> (BigDecimal) o[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new VendorOpportunity(e.getKey(), vById.get(e.getKey()).getLegalName(), e.getValue().size(), amt, ben);
        }).sorted(Comparator.comparing(VendorOpportunity::benefitCad).reversed()).toList();
        int accepting = (int) vById.values().stream().filter(Vendor::isAcceptsCardPayment).count();
        return new CardOpportunity(terms, CardPaymentAdvisor.summarize(sched, schedAmt), CardPaymentAdvisor.summarize(missed, missedAmt),
                CardPaymentAdvisor.summarize(onCard, onCardAmt), accepting, rows);
    }
}
