package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;

/** FX2: no FX rate published for the invoice date (weekend/holiday): the previous available rate was used. */
@Component
public class FX2RateFallback implements ValidationRule {
    public String code() { return "FX2"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        if (i.getCurrency() == null || "CAD".equals(i.getCurrency()) || i.getFxRateDate() == null || i.getInvoiceDate() == null
                || i.getFxRateDate().equals(i.getInvoiceDate())) return List.of();
        return List.of(flag("No rate for " + i.getInvoiceDate() + "; used the previous available rate from " + i.getFxRateDate()
                        + " (" + i.getFxRateToCad().stripTrailingZeros().toPlainString() + ")",
                "invoiceDate", i.getInvoiceDate().toString(), "rateDate", i.getFxRateDate().toString(), "rate", i.getFxRateToCad()));
    }
}
