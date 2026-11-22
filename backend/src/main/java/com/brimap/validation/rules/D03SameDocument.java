package com.brimap.validation.rules;

import com.brimap.duplicates.DuplicateScorer;
import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.brimap.validation.ValidationRule.money;

/** D03: identical file (SHA-256), or same vendor + same total + same date under a different invoice number. */
@Component
public class D03SameDocument implements ValidationRule {
    public String code() { return "D03"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        List<FlagDraft> out = new ArrayList<>();
        for (Invoice o : ctx.otherInvoices()) {
            String otherSha = ctx.documentShaByInvoice().get(o.getId());
            if (ctx.documentSha() != null && ctx.documentSha().equals(otherSha)) {
                out.add(flag("Same document as " + o.getId() + " (identical file, SHA-256 " + ctx.documentSha().substring(0, 12) + "...)",
                        "matchedInvoiceId", o.getId(), "sha256", ctx.documentSha(), "score", 1.0));
                continue;
            }
            if (i.getVendorId() != null && i.getVendorId().equals(o.getVendorId()) && DuplicateScorer.sameTotal(i, o)
                    && DuplicateScorer.sameDate(i, o) && !Objects.equals(i.getNormalizedInvoiceNumber(), o.getNormalizedInvoiceNumber())) {
                var s = DuplicateScorer.score(i, ctx.documentSha(), o, otherSha);
                out.add(flag("Same vendor, same total " + money(i.getTotal()) + " and same date " + i.getInvoiceDate() + " as " + o.getId()
                                + " under a different number (" + o.getVendorInvoiceNumber() + " vs " + i.getVendorInvoiceNumber() + "). Score " + s.value(),
                        "matchedInvoiceId", o.getId(), "matchedNumber", o.getVendorInvoiceNumber(), "score", s.value(), "reasons", s.reasons()));
            }
        }
        return out;
    }
}
