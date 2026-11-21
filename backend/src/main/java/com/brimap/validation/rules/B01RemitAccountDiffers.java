package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.model.Vendor;
import com.brimap.model.VendorBankAccount;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.brimap.validation.ValidationRule.last4;

/**
 * B01: the remit-to account printed on the invoice is not the vendor's current VERIFIED account. Note the
 * Cascadia trap: the invoice matches the vendor master (...9679), but the master itself was changed by an
 * unverified email request, so the account we can trust is still the old one (...7504).
 */
@Component
public class B01RemitAccountDiffers implements ValidationRule {
    public String code() { return "B01"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        Vendor v = ctx.vendor();
        String remit = i.getRemitAccount();
        if (v == null || remit == null) return List.of();
        VendorBankAccount active = ctx.activeAccount(v.getId());
        VendorBankAccount verified = ctx.lastVerifiedAccount(v.getId());
        if (active == null) {
            return List.of(flag("Invoice asks for payment to account " + last4(remit) + ", but " + v.getLegalName()
                    + " has no bank account on file (paid by " + v.getPreferredPaymentMethod() + ")", "remitAccount", last4(remit)));
        }
        boolean matchesActive = remit.equals(active.getAccountNumber());
        if (matchesActive && active.isVerified()) return List.of();
        if (matchesActive) {
            return List.of(flag("Invoice asks for payment to " + last4(remit) + ". That matches the vendor master, but the master was changed to it"
                            + (active.getBankChangeRequestId() == null ? "" : " by " + active.getBankChangeRequestId())
                            + " without verification; the last verified account is " + (verified == null ? "unknown" : verified.masked()),
                    "remitAccount", last4(remit), "activeAccountVersion", active.getVersion(), "activeVerified", false,
                    "lastVerified", verified == null ? null : verified.masked(), "bankChangeRequestId", active.getBankChangeRequestId()));
        }
        return List.of(flag("Invoice asks for payment to " + last4(remit) + "; the account on file is " + active.masked()
                        + (active.isVerified() ? " (verified)" : " (unverified)"),
                "remitAccount", last4(remit), "accountOnFile", active.masked(), "accountOnFileVerified", active.isVerified()));
    }
}
