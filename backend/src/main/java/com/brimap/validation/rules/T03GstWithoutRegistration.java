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

/** T03: GST/HST charged but there is no GST number on the invoice or on file: the input tax credit is at risk. */
@Component
public class T03GstWithoutRegistration implements ValidationRule {
    public String code() { return "T03"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Vendor v = ctx.vendor();
        if (v != null && !"CA".equals(v.getCountry())) return List.of(); // T02
        Map<String, BigDecimal> charged = T01TaxVsProvince.charged(ctx);
        BigDecimal gst = charged.getOrDefault("GST5", BigDecimal.ZERO).add(charged.getOrDefault("HST13", BigDecimal.ZERO));
        if (gst.signum() == 0) return List.of();
        boolean onInvoice = ctx.invoice().getGstNumberOnInvoice() != null;
        boolean onFile = v != null && v.getGstHstNumber() != null;
        if (onInvoice || onFile) return List.of();
        return List.of(flag(money(gst) + " of GST/HST charged without a GST registration number on the invoice or on file, "
                + "so the input tax credit is at risk", "gstCharged", gst));
    }
}
