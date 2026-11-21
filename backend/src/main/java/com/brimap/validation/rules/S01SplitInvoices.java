package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.brimap.validation.ValidationRule.money;

/**
 * S01: possible split to stay under an approval limit - same vendor + department, at least 2 invoices within 14
 * days, each at or under an approval tier threshold (from the policy table), combined over it. The approval
 * engine then routes every invoice in the group at the combined amount. Only bills that have not been approved yet
 * are grouped (this batch, or history still pending approval): bills approved and paid separately in past months
 * are not a split - grouping them flagged every monthly utility bill (tuned on the dataset, see decisions D-041).
 */
@Component
public class S01SplitInvoices implements ValidationRule {
    public static final int WINDOW_DAYS = 14;
    private static final Set<InvoiceStatus> IGNORE = Set.of(InvoiceStatus.REJECTED, InvoiceStatus.DUPLICATE);
    /** Statuses of bills that still have to go through approval. */
    public static final Set<InvoiceStatus> AWAITING_APPROVAL = Set.of(InvoiceStatus.RECEIVED, InvoiceStatus.EXTRACTED, InvoiceStatus.IN_REVIEW,
            InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.ON_HOLD);

    public String code() { return "S01"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        if (i.getVendorId() == null || i.getDepartmentId() == null || i.getInvoiceDate() == null || i.getTotalCad() == null
                || i.getTotalCad().signum() <= 0) return List.of();
        List<Invoice> group = new ArrayList<>();
        group.add(i);
        for (Invoice o : ctx.otherInvoices()) {
            if (i.getVendorId().equals(o.getVendorId()) && i.getDepartmentId().equals(o.getDepartmentId()) && !IGNORE.contains(o.getStatus())
                    && AWAITING_APPROVAL.contains(o.getStatus())
                    && o.getInvoiceDate() != null && o.getTotalCad() != null && o.getTotalCad().signum() > 0
                    && Math.abs(ChronoUnit.DAYS.between(i.getInvoiceDate(), o.getInvoiceDate())) <= WINDOW_DAYS) {
                group.add(o);
            }
        }
        if (group.size() < 2) return List.of();
        group.sort(Comparator.comparing(Invoice::getInvoiceDate).thenComparing(Invoice::getId));
        BigDecimal combined = group.stream().map(Invoice::getTotalCad).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal max = group.stream().map(Invoice::getTotalCad).max(Comparator.naturalOrder()).orElseThrow();
        // Highest tier the group slips under individually but crosses combined.
        BigDecimal crossed = ctx.approvalThresholds().stream().sorted(Comparator.reverseOrder())
                .filter(t -> max.compareTo(t) <= 0 && combined.compareTo(t) > 0).findFirst().orElse(null);
        if (crossed == null) return List.of();
        boolean sameDay = group.stream().map(Invoice::getInvoiceDate).distinct().count() == 1;
        String vendorName = ctx.vendor() == null ? i.getVendorNameRaw() : ctx.vendor().getLegalName();
        StringJoiner parts = new StringJoiner(" + ");
        group.forEach(g -> parts.add(money(g.getTotalCad())));
        return List.of(flag(group.size() + " " + vendorName + " invoices " + (sameDay ? "all dated " + i.getInvoiceDate() : "within " + WINDOW_DAYS + " days")
                        + " for department " + i.getDepartmentId() + " total " + money(combined) + " (" + parts + "); each is under the "
                        + money(crossed) + " approval limit. Routed at the combined amount.",
                "invoiceIds", group.stream().map(Invoice::getId).toList(),
                "numbers", group.stream().map(g -> String.valueOf(g.getVendorInvoiceNumber())).toList(),
                "combinedCad", combined, "threshold", crossed));
    }

    /** The combined amount the approval engine should route at, from an S01 flag's evidence (or null). */
    public static BigDecimal combinedFromEvidence(Map<String, Object> evidence) {
        Object v = evidence == null ? null : evidence.get("combinedCad");
        return v == null ? null : new BigDecimal(v.toString());
    }
}
