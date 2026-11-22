package com.brimap.validation.rules;

import com.brimap.model.Contract;
import com.brimap.model.InvoiceLine;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.brimap.validation.ValidationRule.money;

/**
 * K03: a time-billed line's unit price exceeds the contract's hourly_rate_cap. A line counts as time-billed when
 * it says hours/hrs/consulting/legal/advisory, or has a quantity above 1; disbursements and fees are ignored.
 */
@Component
public class K03HourlyRateCap implements ValidationRule {
    private static final Pattern HOURLY = Pattern.compile("(hour|hrs|\\bhr\\b|consult|legal|advisory|services|review|audit)");
    private static final Pattern NOT_HOURLY = Pattern.compile("(disburse|filing|fee|expense|travel|mileage)");

    public String code() { return "K03"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Contract c = ctx.contract();
        if (c == null || c.getHourlyRateCap() == null) return List.of();
        List<FlagDraft> out = new ArrayList<>();
        for (InvoiceLine l : ctx.lines()) {
            if (l.getUnitPrice() == null || l.getUnitPrice().compareTo(c.getHourlyRateCap()) <= 0) continue;
            String d = l.getDescription() == null ? "" : l.getDescription().toLowerCase(Locale.ROOT);
            boolean timeBilled = (HOURLY.matcher(d).find() || (l.getQuantity() != null && l.getQuantity().compareTo(BigDecimal.ONE) > 0))
                    && !NOT_HOURLY.matcher(d).find();
            if (!timeBilled) continue;
            out.add(flag("Line " + l.getLineNo() + " (" + l.getDescription() + "): billed " + money(l.getUnitPrice()) + "/h; contract " + c.getId()
                            + " caps the rate at " + money(c.getHourlyRateCap()) + "/h (" + money(l.getUnitPrice().subtract(c.getHourlyRateCap()))
                            + " over" + (l.getQuantity() == null ? "" : ", " + money(l.getUnitPrice().subtract(c.getHourlyRateCap()).multiply(l.getQuantity())) + " in total") + ")",
                    "contractId", c.getId(), "lineNo", l.getLineNo(), "unitPrice", l.getUnitPrice(), "cap", c.getHourlyRateCap()));
        }
        return out;
    }
}
