package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static com.brimap.validation.ValidationRule.money;

/** FX1: invoice not in CAD - show the conversion that every amount rule and the approval routing use. */
@Component
public class FX1ForeignCurrency implements ValidationRule {
    public String code() { return "FX1"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        if (i.getCurrency() == null || "CAD".equals(i.getCurrency()) || i.getTotal() == null) return List.of();
        if (i.getFxRateToCad() == null) {
            return List.of(flag(i.getCurrency() + " invoice with no exchange rate available; CAD amount unknown", "currency", i.getCurrency()));
        }
        NumberFormat n = NumberFormat.getNumberInstance(Locale.CANADA);
        n.setMinimumFractionDigits(2);
        String on = i.getFxRateDate() == null ? "" : " " + DateTimeFormatter.ofPattern("MMM d", Locale.CANADA).format(i.getFxRateDate());
        return List.of(flag(i.getCurrency() + " " + n.format(i.getTotal()) + " = CAD " + money(i.getTotalCad()).replace("$", "") + " at the"
                        + on + " rate " + i.getFxRateToCad().stripTrailingZeros().toPlainString(),
                "currency", i.getCurrency(), "total", i.getTotal(), "rate", i.getFxRateToCad(), "rateDate", String.valueOf(i.getFxRateDate()),
                "totalCad", i.getTotalCad()));
    }
}
