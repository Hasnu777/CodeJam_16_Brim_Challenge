package com.brimap.validation.rules;

import com.brimap.model.InvoiceLine;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static com.brimap.validation.ValidationRule.money;

/** X01: any line where quantity x unit price differs from the line amount by more than $0.01. */
@Component
public class X01LineArithmetic implements ValidationRule {
    static final BigDecimal TOL = new BigDecimal("0.01");

    public String code() { return "X01"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        List<FlagDraft> out = new ArrayList<>();
        for (InvoiceLine l : ctx.lines()) {
            if (l.getQuantity() == null || l.getUnitPrice() == null || l.getAmount() == null) continue;
            BigDecimal expected = l.getQuantity().multiply(l.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
            if (expected.subtract(l.getAmount()).abs().compareTo(TOL) > 0) {
                out.add(flag(String.format("Line %d: %s x %s = %s, invoice shows %s", l.getLineNo(), l.getQuantity().stripTrailingZeros().toPlainString(),
                                money(l.getUnitPrice()), money(expected), money(l.getAmount())),
                        "lineNo", l.getLineNo(), "quantity", l.getQuantity(), "unitPrice", l.getUnitPrice(), "expected", expected, "printed", l.getAmount()));
            }
        }
        return out;
    }
}
