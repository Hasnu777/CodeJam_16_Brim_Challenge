package com.brimap.validation.rules;

import com.brimap.duplicates.DuplicateScorer;
import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.brimap.validation.ValidationRule.money;

/** D01: same vendor and same normalized invoice number as an existing invoice (history or this batch). */
@Component
public class D01DuplicateNumber implements ValidationRule {
    public String code() { return "D01"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        if (i.getVendorId() == null || i.getNormalizedInvoiceNumber() == null) return List.of();
        return ctx.otherInvoices().stream()
                .filter(o -> i.getVendorId().equals(o.getVendorId()) && i.getNormalizedInvoiceNumber().equals(o.getNormalizedInvoiceNumber()))
                .map(o -> {
                    var s = DuplicateScorer.score(i, ctx.documentSha(), o, ctx.documentShaByInvoice().get(o.getId()));
                    return flag("Duplicate of " + o.getId() + " (" + o.getVendorInvoiceNumber() + ", " + money(o.getTotal()) + ", dated "
                                    + o.getInvoiceDate() + ", status " + o.getStatus() + "). Score " + s.value() + ": " + String.join(", ", s.reasons()),
                            "matchedInvoiceId", o.getId(), "matchedNumber", o.getVendorInvoiceNumber(), "matchedTotal", o.getTotal(),
                            "matchedStatus", String.valueOf(o.getStatus()), "score", s.value(), "reasons", s.reasons());
                }).toList();
    }
}
