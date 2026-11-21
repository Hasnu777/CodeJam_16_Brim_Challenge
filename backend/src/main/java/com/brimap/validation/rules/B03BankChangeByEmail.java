package com.brimap.validation.rules;

import com.brimap.model.BankChangeRequest;
import com.brimap.model.Vendor;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** B03: the vendor's most recent bank change was requested by email (even if later verified). */
@Component
public class B03BankChangeByEmail implements ValidationRule {
    public String code() { return "B03"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Vendor v = ctx.vendor();
        if (v == null) return List.of();
        return ctx.bankChanges().stream().filter(c -> v.getId().equals(c.getVendorId()))
                .max(Comparator.comparing(BankChangeRequest::getChangedAt))
                .filter(c -> "email_request".equals(c.getRequestChannel()))
                .map(c -> List.of(flag("Last bank change for " + v.getLegalName() + " (" + c.getId() + ", " + c.getChangedAt()
                                + ") was requested by email" + (c.isVerifiedByCallback() ? " (since verified)" : ""),
                        "bankChangeRequestId", c.getId(), "changedAt", c.getChangedAt().toString(), "verified", c.isVerifiedByCallback())))
                .orElse(List.of());
    }
}
