package com.brimap.validation.rules;

import com.brimap.model.Vendor;
import com.brimap.model.VendorBankAccount;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.brimap.validation.ValidationRule.last4;

/** B04: another vendor record uses the same bank account (duplicate vendor records, or a diverted account). */
@Component
public class B04SharedBankAccount implements ValidationRule {
    public String code() { return "B04"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        String vendorId = ctx.vendor() == null ? null : ctx.vendor().getId();
        String acct = ctx.invoice().getRemitAccount();
        if (acct == null && vendorId != null) {
            VendorBankAccount a = ctx.activeAccount(vendorId);
            acct = a == null ? null : a.getAccountNumber();
        }
        if (acct == null) return List.of();
        String account = acct;
        Map<String, String> names = ctx.vendors().stream().collect(Collectors.toMap(Vendor::getId, Vendor::getLegalName));
        List<String> others = ctx.bankAccounts().stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()) && account.equals(a.getAccountNumber()) && !a.getVendorId().equals(vendorId))
                .map(VendorBankAccount::getVendorId).distinct().toList();
        if (others.isEmpty()) return List.of();
        String who = others.stream().map(id -> id + " " + names.getOrDefault(id, "")).collect(Collectors.joining(", "));
        return List.of(flag("Account " + last4(account) + " also belongs to " + who + (vendorId == null ? "" : "; possible duplicate vendor record"),
                "account", last4(account), "otherVendorIds", others));
    }
}
