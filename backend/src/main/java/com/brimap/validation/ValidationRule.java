package com.brimap.validation;

import com.brimap.model.FlagDictionary;
import com.brimap.seed.FlagDictionarySeed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One rule from the PRD validation catalog. Pure: no I/O, no clock, only the context. */
public interface ValidationRule {
    String code();

    List<FlagDraft> check(InvoiceContext ctx);

    default FlagDraft flag(String message, Object... evidenceKv) {
        FlagDictionary d = FlagDictionarySeed.lookup(code());
        return new FlagDraft(code(), d.getSeverity(), d.getTitle(), message, kv(evidenceKv));
    }

    static Map<String, Object> kv(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1] instanceof BigDecimal b ? b.toPlainString() : kv[i + 1]);
        return m;
    }

    /** $1,234.56 (negative as -$1,234.56). */
    static String money(BigDecimal v) {
        if (v == null) return "n/a";
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.CANADA);
        return f.format(v.setScale(2, RoundingMode.HALF_UP));
    }

    static String last4(String account) {
        return account == null ? "?" : "..." + (account.length() > 4 ? account.substring(account.length() - 4) : account);
    }
}
