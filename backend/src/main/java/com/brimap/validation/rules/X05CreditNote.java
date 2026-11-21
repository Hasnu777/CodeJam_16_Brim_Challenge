package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.brimap.validation.ValidationRule.money;

/**
 * X05: negative total or the document says credit note: it offsets the vendor's next payment. When an earlier
 * invoice from the same vendor has exactly the credited amount, it is named (Fixwell CN-FA-000413 = FA-000410).
 */
@Component
public class X05CreditNote implements ValidationRule {
    public String code() { return "X05"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        boolean negative = i.getTotal() != null && i.getTotal().signum() < 0;
        if (!i.isCreditNote() && !negative) return List.of();
        String vendor = ctx.vendor() != null ? ctx.vendor().getLegalName() : i.getVendorNameRaw();
        Optional<Invoice> credited = i.getTotal() == null || i.getVendorId() == null ? Optional.empty() : ctx.otherInvoices().stream()
                .filter(o -> i.getVendorId().equals(o.getVendorId()) && o.getTotal() != null && o.getTotal().compareTo(i.getTotal().abs()) == 0)
                .max(Comparator.comparing(o -> o.getInvoiceDate() == null ? java.time.LocalDate.MIN : o.getInvoiceDate()));
        String ref = credited.map(o -> " It matches " + o.getVendorInvoiceNumber() + " (" + o.getId() + ", " + o.getStatus() + ") for the same amount.")
                .orElse("");
        return List.of(flag("Credit note for " + money(i.getTotal() == null ? null : i.getTotal().abs()) + "; it will offset the next payment to "
                        + vendor + " instead of being paid out." + ref,
                "total", i.getTotal(), "vendorId", i.getVendorId(), "creditsInvoiceId", credited.map(Invoice::getId).orElse(null)));
    }
}
