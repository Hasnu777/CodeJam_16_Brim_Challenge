package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.model.InvoiceLine;
import com.brimap.model.InvoiceTax;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.brimap.validation.ValidationRule.money;

/** X02: lines don't sum to the subtotal, or subtotal + taxes doesn't equal the total. */
@Component
public class X02TotalsAddUp implements ValidationRule {
    static final BigDecimal TOL = new BigDecimal("0.01");

    public String code() { return "X02"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        List<FlagDraft> out = new ArrayList<>();
        if (!ctx.lines().isEmpty() && i.getSubtotal() != null) {
            BigDecimal sum = ctx.lines().stream().map(InvoiceLine::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.subtract(i.getSubtotal()).abs().compareTo(TOL) > 0) {
                out.add(flag("Lines add to " + money(sum) + "; subtotal printed as " + money(i.getSubtotal()),
                        "linesSum", sum, "subtotal", i.getSubtotal(), "difference", i.getSubtotal().subtract(sum)));
            }
        }
        if (i.getSubtotal() != null && i.getTotal() != null) {
            BigDecimal tax = ctx.taxes().isEmpty()
                    ? nz(i.getGstHstAmount()).add(nz(i.getPstQstAmount()))
                    : ctx.taxes().stream().map(InvoiceTax::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal expected = i.getSubtotal().add(tax);
            if (expected.subtract(i.getTotal()).abs().compareTo(TOL) > 0) {
                out.add(flag("Subtotal " + money(i.getSubtotal()) + " + tax " + money(tax) + " = " + money(expected)
                                + ", but the total is printed as " + money(i.getTotal()) + " (" + money(i.getTotal().subtract(expected)) + " difference)",
                        "subtotal", i.getSubtotal(), "tax", tax, "expectedTotal", expected, "printedTotal", i.getTotal(),
                        "difference", i.getTotal().subtract(expected)));
            }
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
