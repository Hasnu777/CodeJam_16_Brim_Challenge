package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.model.PurchaseOrder;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

import static com.brimap.validation.ValidationRule.money;

/**
 * P12 (policy): invoice exceeds the PO's remaining balance (PO total minus what other non-rejected invoices have
 * already billed against it, pre-tax). Routing adds a Finance Manager step for these.
 */
@Component
public class P12OverPoBalance implements ValidationRule {
    public String code() { return "P12"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        PurchaseOrder po = ctx.po();
        if (po == null || po.getTotalAmount() == null || i.getSubtotal() == null || i.isCreditNote()) return List.of();
        BigDecimal consumed = ctx.poConsumed() == null ? BigDecimal.ZERO : ctx.poConsumed();
        BigDecimal remaining = po.getTotalAmount().subtract(consumed);
        if (i.getSubtotal().compareTo(remaining) <= 0) return List.of();
        return List.of(flag("Exceeds " + po.getId() + " remaining " + money(remaining) + " (of " + money(po.getTotalAmount()) + ") by "
                        + money(i.getSubtotal().subtract(remaining)) + "; Finance Manager step added",
                "poId", po.getId(), "poTotal", po.getTotalAmount(), "alreadyBilled", consumed, "remaining", remaining,
                "invoiceSubtotal", i.getSubtotal(), "overBy", i.getSubtotal().subtract(remaining)));
    }
}
