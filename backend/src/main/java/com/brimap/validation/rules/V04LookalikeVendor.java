package com.brimap.validation.rules;

import com.brimap.extraction.VendorMatcher;
import com.brimap.model.Invoice;
import com.brimap.model.Vendor;
import com.brimap.model.VendorBankAccount;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * V04: the billing vendor's name is >= 0.85 similar to ANOTHER vendor, and they differ in bank account or email
 * domain (where the money goes / who is writing), and the other vendor is the established one (more paid
 * invoices in history). Classic impersonation: "Harlow and Stern LLP" (V052, no history) vs "Harlow & Stern LLP"
 * (V026). Same bank + same domain with a different GST # is a duplicate vendor record, not an impersonation
 * (B04 reports the shared account).
 */
@Component
public class V04LookalikeVendor implements ValidationRule {
    public static final double THRESHOLD = 0.85;

    public String code() { return "V04"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Vendor v = ctx.vendor();
        String name = v != null ? v.getLegalName() : ctx.invoice().getVendorNameRaw();
        if (name == null) return List.of();
        long myHistory = v == null ? 0 : paidCount(ctx, v.getId());
        List<FlagDraft> out = new ArrayList<>();
        for (Vendor other : ctx.vendors()) {
            if (v != null && other.getId().equals(v.getId())) continue;
            double sim = VendorMatcher.similarity(name, other.getLegalName());
            if (sim < THRESHOLD) continue;
            VendorBankAccount mine = v != null ? ctx.activeAccount(v.getId()) : null;
            VendorBankAccount theirs = ctx.activeAccount(other.getId());
            String myAcct = ctx.invoice().getRemitAccount() != null ? ctx.invoice().getRemitAccount() : mine == null ? null : mine.getAccountNumber();
            boolean bankDiffers = myAcct != null && theirs != null && !myAcct.equals(theirs.getAccountNumber());
            String myDomain = v != null ? v.emailDomain() : VendorMatcher.domain(ctx.invoice().getSenderEmail());
            boolean domainDiffers = myDomain != null && other.emailDomain() != null && !myDomain.equals(other.emailDomain());
            if (!bankDiffers && !domainDiffers) continue;
            long theirHistory = paidCount(ctx, other.getId());
            if (theirHistory <= myHistory) continue; // we are the established vendor; the other record is the suspicious one
            String myGst = v != null ? v.getGstHstNumber() : ctx.invoice().getGstNumberOnInvoice();
            List<String> diffs = new ArrayList<>();
            if (!Objects.equals(VendorMatcher.canonicalGst(myGst), VendorMatcher.canonicalGst(other.getGstHstNumber()))) {
                diffs.add("GST # " + myGst + " vs " + other.getGstHstNumber());
            }
            if (bankDiffers) diffs.add("bank account " + ValidationRule.last4(myAcct) + " vs " + theirs.masked());
            if (domainDiffers) diffs.add("email domain " + myDomain + " vs " + other.emailDomain());
            out.add(flag(String.format("\"%s\"%s closely resembles \"%s\" (%s, %d paid invoices; this one has %d), similarity %.2f, "
                                    + "but has a different %s. Confirm who you are paying.",
                            name, v != null ? " (" + v.getId() + ")" : "", other.getLegalName(), other.getId(), theirHistory, myHistory, sim,
                            String.join(", ", diffs)),
                    "vendorId", v == null ? null : v.getId(), "resemblesVendorId", other.getId(), "similarity", Math.round(sim * 100) / 100.0,
                    "differences", diffs, "theirPaidInvoices", theirHistory, "ourPaidInvoices", myHistory));
        }
        return out;
    }

    private static long paidCount(InvoiceContext ctx, String vendorId) {
        return ctx.otherInvoices().stream().filter(i -> vendorId.equals(i.getVendorId()) && i.isHistorical()
                && i.getStatus() == com.brimap.model.InvoiceStatus.PAID).count();
    }
}
