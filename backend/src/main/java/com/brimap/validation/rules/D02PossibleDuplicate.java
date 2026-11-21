package com.brimap.validation.rules;

import com.brimap.duplicates.DuplicateScorer;
import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.brimap.validation.ValidationRule.money;

/**
 * D02: near-duplicate - same vendor, total within 1%, dates within 30 days, invoice # differs by 1-2 characters
 * (reformatted or retyped numbers). Exact number matches are D01.
 * The date window is 10 days, not the PRD's 30: with 30, every monthly subscription bill (same amount,
 * next sequential number, ~30 days later) was flagged against last month's (tuned on the dataset, decisions D-042).
 */
@Component
public class D02PossibleDuplicate implements ValidationRule {
    public static final int WINDOW_DAYS = 10;

    public String code() { return "D02"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        if (i.getVendorId() == null || i.getNormalizedInvoiceNumber() == null) return List.of();
        return ctx.otherInvoices().stream()
                .filter(o -> i.getVendorId().equals(o.getVendorId()) && o.getNormalizedInvoiceNumber() != null)
                .map(o -> new Object[]{o, DuplicateScorer.score(i, ctx.documentSha(), o, ctx.documentShaByInvoice().get(o.getId()))})
                .filter(p -> {
                    var s = (DuplicateScorer.Score) p[1];
                    Invoice o = (Invoice) p[0];
                    // "differs by <= 2 characters" only means something for longer numbers: 279 vs 281 is not a retyping
                    int len = Math.max(i.getNormalizedInvoiceNumber().length(), o.getNormalizedInvoiceNumber().length());
                    return !s.sameNumber() && s.numberDistance() <= 2 && s.numberDistance() * 2 < len && s.amountClose() && i.getInvoiceDate() != null && o.getInvoiceDate() != null
                            && Math.abs(ChronoUnit.DAYS.between(i.getInvoiceDate(), o.getInvoiceDate())) <= WINDOW_DAYS;
                })
                .map(p -> {
                    Invoice o = (Invoice) p[0];
                    var s = (DuplicateScorer.Score) p[1];
                    long days = Math.abs(ChronoUnit.DAYS.between(i.getInvoiceDate(), o.getInvoiceDate()));
                    return flag("Possible duplicate of " + o.getId() + " (" + o.getVendorInvoiceNumber() + ", same vendor, " + money(o.getTotal()) + ", "
                                    + days + " days apart). Score " + s.value() + ": " + String.join(", ", s.reasons()),
                            "matchedInvoiceId", o.getId(), "matchedNumber", o.getVendorInvoiceNumber(), "matchedTotal", o.getTotal(),
                            "daysApart", days, "score", s.value(), "reasons", s.reasons());
                }).toList();
    }
}
