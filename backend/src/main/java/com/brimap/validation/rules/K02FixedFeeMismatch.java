package com.brimap.validation.rules;

import com.brimap.model.Contract;
import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.brimap.validation.ValidationRule.money;

/** K02: fixed_monthly contract and the invoice subtotal differs from the monthly fee. */
@Component
public class K02FixedFeeMismatch implements ValidationRule {
    public String code() { return "K02"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Contract c = ctx.contract();
        Invoice i = ctx.invoice();
        if (c == null || !"fixed_monthly".equals(c.getBillingType()) || c.getMonthlyFee() == null || i.getSubtotal() == null || i.isCreditNote()) {
            return List.of();
        }
        if (i.getSubtotal().compareTo(c.getMonthlyFee()) == 0) return List.of();
        BigDecimal diff = i.getSubtotal().subtract(c.getMonthlyFee());
        BigDecimal pct = diff.multiply(BigDecimal.valueOf(100)).divide(c.getMonthlyFee(), 1, RoundingMode.HALF_UP);
        String cur = c.getCurrency() == null || "CAD".equals(c.getCurrency()) ? "" : c.getCurrency() + " ";
        return List.of(flag("Contract " + c.getId() + " fee is " + cur + money(c.getMonthlyFee()) + "/month; invoice subtotal is " + cur
                        + money(i.getSubtotal()) + " (" + (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%)",
                "contractId", c.getId(), "monthlyFee", c.getMonthlyFee(), "subtotal", i.getSubtotal(), "difference", diff, "pct", pct));
    }
}
