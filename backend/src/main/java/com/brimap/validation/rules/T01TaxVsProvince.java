package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.model.InvoiceLine;
import com.brimap.model.InvoiceTax;
import com.brimap.model.Vendor;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.brimap.validation.ValidationRule.money;

/**
 * T01: tax charged differs from what the ship-to province requires (tolerance $0.05 per tax). Rates come from the
 * dataset's tax_codes table, never constants: ON HST13; QC GST5 + QST9975 on the pre-tax amount; BC GST5 + PST7
 * on goods lines only; AB and other provinces GST5. Foreign vendors and exempt categories are T02's job;
 * unregistered small suppliers that charge nothing are skipped (charging without a number is T03).
 */
@Component
public class T01TaxVsProvince implements ValidationRule {
    static final BigDecimal TOL = new BigDecimal("0.05");
    private static final Map<String, String> NAMES = Map.of("GST5", "GST", "HST13", "HST", "QST9975", "QST", "PST7_BC", "PST");

    public String code() { return "T01"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        Vendor v = ctx.vendor();
        String prov = i.getShipToProvince();
        if (prov == null || i.getSubtotal() == null) return List.of();
        if (v != null && (!"CA".equals(v.getCountry()) || "Insurance".equals(v.getCategory()))) return List.of();
        Map<String, BigDecimal> charged = charged(ctx);
        boolean registered = (v != null && v.getGstHstNumber() != null) || i.getGstNumberOnInvoice() != null;
        if (!registered && charged.isEmpty()) return List.of();

        BigDecimal goods = ctx.lines().stream().filter(l -> "goods".equals(l.getLineType())).map(InvoiceLine::getAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> expected = expected(ctx, prov, i.getSubtotal(), goods);

        List<String> problems = new ArrayList<>();
        Set<String> codes = new TreeSet<>(expected.keySet());
        codes.addAll(charged.keySet());
        for (String c : codes) {
            BigDecimal e = expected.getOrDefault(c, BigDecimal.ZERO), ch = charged.getOrDefault(c, BigDecimal.ZERO);
            if (e.subtract(ch).abs().compareTo(TOL) > 0) problems.add(c);
        }
        if (problems.isEmpty()) return List.of();
        String exp = describe(expected);
        String chg = charged.isEmpty() ? "no tax" : describe(charged) + (charged.size() < expected.size() ? " only" : "");
        return List.of(flag("Delivered to " + prov + ": expected " + exp + "; invoice charges " + chg,
                "shipToProvince", prov, "expected", stringify(expected), "charged", stringify(charged), "mismatched", problems,
                "taxBase", i.getSubtotal(), "goodsBase", goods));
    }

    static Map<String, BigDecimal> expected(InvoiceContext ctx, String prov, BigDecimal base, BigDecimal goodsBase) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        switch (prov) {
            case "ON" -> m.put("HST13", pct(base, ctx.rate("HST13")));
            case "QC" -> { m.put("GST5", pct(base, ctx.rate("GST5"))); m.put("QST9975", pct(base, ctx.rate("QST9975"))); }
            case "BC" -> {
                m.put("GST5", pct(base, ctx.rate("GST5")));
                if (goodsBase.signum() != 0) m.put("PST7_BC", pct(goodsBase, ctx.rate("PST7_BC")));
            }
            default -> m.put("GST5", pct(base, ctx.rate("GST5")));
        }
        return m;
    }

    static Map<String, BigDecimal> charged(InvoiceContext ctx) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (InvoiceTax t : ctx.taxes()) {
            if (t.getAmount() == null || t.getTaxCode() == null) continue;
            m.merge(t.getTaxCode(), t.getAmount(), BigDecimal::add);
        }
        if (m.isEmpty()) { // history rows carry totals only
            Invoice i = ctx.invoice();
            if (i.getGstHstAmount() != null && i.getGstHstAmount().signum() != 0) m.put("GST5", i.getGstHstAmount());
            if (i.getPstQstAmount() != null && i.getPstQstAmount().signum() != 0) m.put("QST9975", i.getPstQstAmount());
        }
        return m;
    }

    private static BigDecimal pct(BigDecimal base, BigDecimal rate) { return base.multiply(rate).setScale(2, RoundingMode.HALF_UP); }

    private static String describe(Map<String, BigDecimal> m) {
        StringJoiner j = new StringJoiner(" + ");
        m.forEach((k, v) -> j.add(NAMES.getOrDefault(k, k) + " " + money(v)));
        return j.toString();
    }

    private static Map<String, String> stringify(Map<String, BigDecimal> m) {
        Map<String, String> o = new LinkedHashMap<>();
        m.forEach((k, v) -> o.put(k, v.toPlainString()));
        return o;
    }
}
