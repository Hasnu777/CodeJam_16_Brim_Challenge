package com.brimap.purchasing;

import com.brimap.model.Invoice;
import com.brimap.model.PurchaseOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.brimap.validation.ValidationRule.money;

/**
 * Ranks the purchase orders a clerk could link to an invoice that has none (or a bad one). Pure function, no DB.
 * <p>
 * Candidates: same vendor, status open or partially_invoiced. The vendor is what identifies the PO; the date and the
 * amount are sanity checks. Ranking: POs that pass every check first (issued on or before the invoice date, remaining
 * balance covers the pre-tax amount, same currency), then same department, then the most recently issued before the
 * invoice. Nothing is linked automatically: the clerk confirms (P13 is a control, not a guess).
 */
public final class PoSuggester {
    private PoSuggester() {}

    static final Set<String> LINKABLE = Set.of("open", "partially_invoiced");

    public record Suggestion(String poId, String status, LocalDate issueDate, String departmentId, String requesterUserId,
                             String description, String currency, BigDecimal total, BigDecimal billed, BigDecimal remaining,
                             boolean bestMatch, List<String> reasons, List<String> warnings) {}

    /**
     * @param inv       the invoice (vendorId, invoiceDate, subtotal, currency, departmentId are used)
     * @param vendorPos every PO of the invoice's vendor
     * @param billed    pre-tax amount already billed per PO id by OTHER non-rejected invoices
     */
    public static List<Suggestion> rank(Invoice inv, List<PurchaseOrder> vendorPos, Map<String, BigDecimal> billed) {
        if (inv.getVendorId() == null) return List.of();
        List<Scored> scored = new ArrayList<>();
        for (PurchaseOrder po : vendorPos) {
            if (!inv.getVendorId().equals(po.getVendorId())) continue;
            if (po.getStatus() == null || !LINKABLE.contains(po.getStatus().toLowerCase())) continue;
            scored.add(score(inv, po, billed.getOrDefault(po.getId(), BigDecimal.ZERO)));
        }
        scored.sort(Comparator.comparingInt((Scored s) -> s.warnings.size())
                .thenComparing(s -> !s.sameDept)
                .thenComparingLong(s -> s.daysBefore));
        List<Suggestion> out = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);
            PurchaseOrder po = s.po;
            out.add(new Suggestion(po.getId(), po.getStatus(), po.getIssueDate(), po.getDepartmentId(), po.getRequesterUserId(),
                    po.getDescription(), po.getCurrency(), po.getTotalAmount(), s.billed, s.remaining,
                    i == 0 && s.warnings.isEmpty(), s.reasons, s.warnings));
        }
        return out;
    }

    private record Scored(PurchaseOrder po, BigDecimal billed, BigDecimal remaining, boolean sameDept, long daysBefore,
                          List<String> reasons, List<String> warnings) {}

    private static Scored score(Invoice inv, PurchaseOrder po, BigDecimal billed) {
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        reasons.add("Same vendor, PO is " + po.getStatus().replace('_', ' '));

        long daysBefore = Long.MAX_VALUE / 2; // unknown dates sort last among equals
        if (po.getIssueDate() != null && inv.getInvoiceDate() != null) {
            long d = ChronoUnit.DAYS.between(po.getIssueDate(), inv.getInvoiceDate());
            if (d >= 0) {
                daysBefore = d;
                reasons.add("Issued " + (d == 0 ? "the same day as" : d + (d == 1 ? " day" : " days") + " before") + " the invoice");
            } else {
                daysBefore = Long.MAX_VALUE / 2 + -d;
                warnings.add("Issued " + -d + (d == -1 ? " day" : " days") + " after the invoice date");
            }
        }

        BigDecimal total = po.getTotalAmount() == null ? BigDecimal.ZERO : po.getTotalAmount();
        BigDecimal remaining = total.subtract(billed);
        if (inv.getSubtotal() != null && !inv.isCreditNote()) {
            if (inv.getSubtotal().compareTo(remaining) <= 0) {
                reasons.add(money(remaining) + " remaining covers the " + money(inv.getSubtotal()) + " pre-tax amount");
            } else {
                warnings.add("Only " + money(remaining) + " remaining; invoice is " + money(inv.getSubtotal())
                        + " pre-tax (over by " + money(inv.getSubtotal().subtract(remaining)) + ", adds a Finance Manager step)");
            }
        }

        if (po.getCurrency() != null && inv.getCurrency() != null && !po.getCurrency().equalsIgnoreCase(inv.getCurrency())) {
            warnings.add("PO is in " + po.getCurrency() + ", invoice is in " + inv.getCurrency());
        }

        boolean sameDept = inv.getDepartmentId() != null && Objects.equals(inv.getDepartmentId(), po.getDepartmentId());
        if (sameDept) reasons.add("Same department: " + po.getDepartmentId()); // the UI shows the name

        return new Scored(po, billed, remaining, sameDept, daysBefore, reasons, warnings);
    }
}
