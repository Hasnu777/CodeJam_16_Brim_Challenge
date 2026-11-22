package com.brimap.validation.rules;

import com.brimap.model.Vendor;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;

/** P13 (policy): vendor requires a purchase order and none is referenced -> hold. */
@Component
public class P13PoRequired implements ValidationRule {
    public String code() { return "P13"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Vendor v = ctx.vendor();
        if (v == null || !v.isRequiresPo() || ctx.invoice().getPoId() != null || ctx.invoice().isCreditNote()) return List.of();
        return List.of(flag(v.getLegalName() + " (" + v.getCategory() + ") requires a PO; none is referenced on the invoice. Add the PO number or get one raised.",
                "vendorId", v.getId(), "category", v.getCategory()));
    }
}
