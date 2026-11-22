package com.brimap.duplicates;

import java.util.Locale;
import java.util.regex.Pattern;

/** Invoice-number normalization used for duplicate detection. */
public final class InvoiceNumbers {
    private InvoiceNumbers() {}

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]");
    /** Generic prefixes that vendors add or drop between systems. CN (credit note) is deliberately NOT stripped. */
    private static final Pattern PREFIX = Pattern.compile("^(INVOICE|INV|FACTURE|FA|NO|NUM|N)(?=\\d)");

    /**
     * Uppercase, strip non-alphanumerics, strip a generic prefix (INV, FA, ...) when followed by digits,
     * strip leading zeros. "INV-01561" -> "1561", "FA-000557" -> "557", "000961" -> "961", "CN-FA-000413" -> "CNFA413".
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = NON_ALNUM.matcher(raw.toUpperCase(Locale.ROOT)).replaceAll("");
        s = PREFIX.matcher(s).replaceFirst("");
        if (s.startsWith("CN")) {
            String rest = s.substring(2);
            rest = PREFIX.matcher(rest).replaceFirst("");
            return "CN" + stripZeros(rest);
        }
        return stripZeros(s);
    }

    private static String stripZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        return s.substring(i);
    }

    /** Levenshtein distance, for the "differs by at most 2 characters" near-duplicate rule. */
    public static int distance(String a, String b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
