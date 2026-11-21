package com.brimap.validation.rules;

import com.brimap.model.Vendor;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;

/** V02: vendor is pending_review or on_hold. Payment is held until a Finance Manager approves the vendor. */
@Component
public class V02VendorNotApproved implements ValidationRule {
    public String code() { return "V02"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Vendor v = ctx.vendor();
        if (v == null || "active".equals(v.getStatus())) return List.of();
        String what = "on_hold".equals(v.getStatus()) ? "is on hold" : "is pending review";
        return List.of(flag(v.getLegalName() + " (" + v.getId() + ") " + what + "; Finance Manager approval of the vendor is needed before payment",
                "vendorId", v.getId(), "vendorStatus", v.getStatus()));
    }
}
