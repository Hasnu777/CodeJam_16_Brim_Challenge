package com.brimap.validation.rules;

import com.brimap.extraction.VendorMatcher;
import com.brimap.model.Vendor;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * V06 (fraud alerts bonus): the email came from a domain that looks like ANOTHER vendor's domain but isn't it
 * ("harlowandstern-llp.example" vs Harlow & Stern's "harlow.example"; "cascadia-cartage.example" vs
 * "cascadiacartage.example"). Compares the domain names without the TLD, punctuation-insensitive.
 */
@Component
public class V06LookalikeSenderDomain implements ValidationRule {
    public static final double THRESHOLD = 0.88;
    private static final JaroWinklerSimilarity JW = new JaroWinklerSimilarity();

    public String code() { return "V06"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        String sender = VendorMatcher.domain(ctx.invoice().getSenderEmail());
        if (sender == null) return List.of();
        String s = stem(sender);
        Vendor billing = ctx.vendor();
        List<FlagDraft> out = new ArrayList<>();
        for (Vendor v : ctx.vendors()) {
            String d = v.emailDomain();
            if (d == null || d.equals(sender) || (billing != null && v.getId().equals(billing.getId()))) continue;
            String t = stem(d);
            boolean contains = t.length() >= 5 && s.contains(t) || s.length() >= 5 && t.contains(s);
            double sim = JW.apply(s, t);
            if (sim < THRESHOLD && !contains) continue;
            // Like V04: a vendor writing from its own registered domain, with at least as much paid history as the
            // vendor it resembles, is the original - the other record is the imitation (genuine Harlow & Stern mail).
            if (billing != null && sender.equals(billing.emailDomain()) && paid(ctx, billing.getId()) >= paid(ctx, v.getId())) continue;
            out.add(flag(String.format("Sender domain %s looks like %s's domain %s (similarity %.2f) but is not it", sender, v.getLegalName(), d, Math.max(sim, contains ? 0.9 : 0)),
                    "senderDomain", sender, "resemblesVendorId", v.getId(), "vendorDomain", d));
        }
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    private static long paid(InvoiceContext ctx, String vendorId) {
        return ctx.otherInvoices().stream().filter(i -> vendorId.equals(i.getVendorId()) && i.isHistorical()
                && i.getStatus() == com.brimap.model.InvoiceStatus.PAID).count();
    }

    static String stem(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        int dot = d.lastIndexOf('.');
        if (dot > 0) d = d.substring(0, dot);
        return d.replaceAll("[^a-z0-9]", "");
    }
}
