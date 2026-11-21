package com.brimap.validation.rules;

import com.brimap.model.Contract;
import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** K01: the linked contract ended before the invoice date (or is marked expired). */
@Component
public class K01ContractExpired implements ValidationRule {
    public String code() { return "K01"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Contract c = ctx.contract();
        Invoice i = ctx.invoice();
        if (c == null || i.getInvoiceDate() == null) return List.of();
        boolean ended = c.getEndDate() != null && c.getEndDate().isBefore(i.getInvoiceDate());
        if (!ended && !"expired".equalsIgnoreCase(c.getStatus())) return List.of();
        String vendor = ctx.vendor() == null ? c.getVendorId() : ctx.vendor().getLegalName();
        return List.of(flag(vendor + " contract " + c.getId() + " (" + c.getDescription() + ") ended "
                        + (c.getEndDate() == null ? "" : DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.CANADA).format(c.getEndDate()))
                        + "; invoice is dated " + i.getInvoiceDate() + (c.isAutoRenew() ? " (contract says auto-renew, but its status is " + c.getStatus() + ")" : ""),
                "contractId", c.getId(), "endDate", String.valueOf(c.getEndDate()), "status", c.getStatus(), "invoiceDate", i.getInvoiceDate().toString()));
    }
}
