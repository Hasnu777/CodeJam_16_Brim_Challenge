package com.brimap.validation.rules;

import com.brimap.extraction.VendorMatcher;
import com.brimap.model.Vendor;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;

/** V05: the email sender's domain differs from the vendor's AP contact domain. */
@Component
public class V05SenderDomainMismatch implements ValidationRule {
    public String code() { return "V05"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Vendor v = ctx.vendor();
        String sender = ctx.invoice().getSenderEmail();
        String domain = VendorMatcher.domain(sender);
        if (v == null || domain == null || v.emailDomain() == null || domain.equals(v.emailDomain())) return List.of();
        return List.of(flag("Sent from " + domain + "; " + v.getLegalName() + "'s contact on file is " + v.getApContactEmail(),
                "sender", sender, "vendorContact", v.getApContactEmail()));
    }
}
