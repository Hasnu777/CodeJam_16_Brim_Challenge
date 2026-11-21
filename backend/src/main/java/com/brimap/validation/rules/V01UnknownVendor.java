package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;

/** V01: no vendor match by GST number, sender domain or name. Blocks payment until the vendor is onboarded. */
@Component
public class V01UnknownVendor implements ValidationRule {
    public String code() { return "V01"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        if (ctx.vendor() != null) return List.of();
        String name = i.getVendorNameRaw() == null ? "The supplier" : i.getVendorNameRaw();
        return List.of(flag(name + " is not in the vendor master (no match on GST number "
                        + (i.getGstNumberOnInvoice() == null ? "(none printed)" : i.getGstNumberOnInvoice())
                        + ", sender " + (i.getSenderEmail() == null ? "(uploaded)" : i.getSenderEmail()) + " or name). Onboard the vendor before payment.",
                "vendorName", i.getVendorNameRaw(), "gstNumber", i.getGstNumberOnInvoice(), "sender", i.getSenderEmail()));
    }
}
