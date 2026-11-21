package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** X04: invoice dated after today, due before it was issued, or older than 180 days. */
@Component
public class X04SuspiciousDates implements ValidationRule {
    static final int MAX_AGE_DAYS = 180;

    public String code() { return "X04"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        List<FlagDraft> out = new ArrayList<>();
        if (i.getInvoiceDate() == null) return out;
        if (i.getInvoiceDate().isAfter(ctx.today())) {
            long days = ChronoUnit.DAYS.between(ctx.today(), i.getInvoiceDate());
            out.add(flag("Invoice dated " + i.getInvoiceDate() + ", " + days + " days in the future (today is " + ctx.today()
                    + "); likely a typo or a pre-billed invoice", "invoiceDate", i.getInvoiceDate().toString(), "today", ctx.today().toString()));
        }
        if (i.getDueDate() != null && i.getDueDate().isBefore(i.getInvoiceDate())) {
            out.add(flag("Due date " + i.getDueDate() + " is before the invoice date " + i.getInvoiceDate(),
                    "invoiceDate", i.getInvoiceDate().toString(), "dueDate", i.getDueDate().toString()));
        }
        long age = ChronoUnit.DAYS.between(i.getInvoiceDate(), ctx.today());
        if (age > MAX_AGE_DAYS) {
            out.add(flag("Invoice is " + age + " days old (dated " + i.getInvoiceDate() + "); check it was not already paid",
                    "invoiceDate", i.getInvoiceDate().toString(), "ageDays", age));
        }
        return out;
    }
}
