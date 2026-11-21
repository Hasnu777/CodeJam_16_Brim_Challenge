package com.brimap.validation.rules;

import com.brimap.model.Vendor;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.brimap.validation.ValidationRule.money;

/** T02: a foreign vendor charged Canadian tax, or an exempt category (Insurance) was taxed. */
@Component
public class T02TaxNotDue implements ValidationRule {
    public String code() { return "T02"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Vendor v = ctx.vendor();
        if (v == null) return List.of();
        Map<String, BigDecimal> charged = T01TaxVsProvince.charged(ctx);
        BigDecimal total = charged.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) return List.of();
        if (!"CA".equals(v.getCountry())) {
            return List.of(flag(v.getLegalName() + " is a foreign (" + v.getCountry() + ") vendor with no Canadian registration, but "
                    + money(total) + " of Canadian tax was charged (tax code FOREIGN expected)", "country", v.getCountry(), "taxCharged", total));
        }
        if ("Insurance".equals(v.getCategory())) {
            return List.of(flag("Insurance premiums are exempt, but " + money(total) + " of tax was charged (tax code EXEMPT expected)",
                    "category", v.getCategory(), "taxCharged", total));
        }
        return List.of();
    }
}
