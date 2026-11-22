package com.brimap.validation.rules;

import com.brimap.model.Contract;
import com.brimap.model.InvoiceLine;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.brimap.validation.ValidationRule.money;

/**
 * K04: fuel surcharge above the contract's fuel_surcharge_cap_pct. The percentage is COMPUTED (surcharge amount /
 * the other freight lines), not trusted from the printed label.
 */
@Component
public class K04FuelSurchargeCap implements ValidationRule {
    public String code() { return "K04"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Contract c = ctx.contract();
        if (c == null || c.getFuelSurchargeCapPct() == null) return List.of();
        BigDecimal fuel = BigDecimal.ZERO, base = BigDecimal.ZERO;
        for (InvoiceLine l : ctx.lines()) {
            if (l.getAmount() == null) continue;
            if (l.getDescription() != null && l.getDescription().toLowerCase(Locale.ROOT).contains("fuel")) fuel = fuel.add(l.getAmount());
            else base = base.add(l.getAmount());
        }
        if (fuel.signum() <= 0 || base.signum() <= 0) return List.of();
        BigDecimal pct = fuel.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
        if (pct.compareTo(c.getFuelSurchargeCapPct()) <= 0) return List.of();
        BigDecimal allowed = base.multiply(c.getFuelSurchargeCapPct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        String printed = ctx.lines().stream().map(InvoiceLine::getDescription).filter(Objects::nonNull)
                .filter(d -> d.toLowerCase(Locale.ROOT).contains("fuel")).findFirst().orElse("");
        return List.of(flag("Fuel surcharge " + money(fuel) + " is " + pct.toPlainString() + "% of " + money(base) + " freight; contract "
                        + c.getId() + " caps it at " + c.getFuelSurchargeCapPct().stripTrailingZeros().toPlainString() + "% (" + money(allowed)
                        + "), so " + money(fuel.subtract(allowed)) + " over" + (printed.isEmpty() ? "" : ". Line reads \"" + printed + "\""),
                "contractId", c.getId(), "surcharge", fuel, "freightBase", base, "pct", pct, "capPct", c.getFuelSurchargeCapPct(),
                "overBy", fuel.subtract(allowed)));
    }
}
