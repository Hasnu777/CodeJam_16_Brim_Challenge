package com.brimap.extraction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic normalization of what the extractor read. Pure functions, unit-tested.
 * Handles English ("$1,488.34", "-$1,042.76", "(1,614.21)") and French ("1 234,56 $", "9,975 %") formats,
 * many date formats, and maps tax labels (GST/TPS, HST/TVH, QST/TVQ, PST) to the dataset's tax codes.
 */
public final class InvoiceNormalizer {
    private InvoiceNormalizer() {}

    /** Parse an amount as printed. Returns null if there is no number in it. */
    public static BigDecimal amount(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace(' ', ' ').replace(' ', ' ');
        if (s.isEmpty()) return null;
        boolean negative = s.startsWith("-") || s.startsWith("−") || s.endsWith("-") || (s.startsWith("(") && s.endsWith(")"))
                || s.contains("-$") || s.contains("$-");
        // keep digits and separators only
        String n = s.replaceAll("[^0-9.,' ]", "").trim();
        if (n.replaceAll("[^0-9]", "").isEmpty()) return null;
        n = n.replace("'", "");
        int lastDot = n.lastIndexOf('.'), lastComma = n.lastIndexOf(',');
        String normalized;
        if (lastComma > lastDot) {
            // comma is the decimal separator if followed by 1-2 digits (French "1 234,56"), else thousands ("1,234")
            String after = n.substring(lastComma + 1).trim();
            if (after.length() <= 2 && after.matches("\\d+")) {
                normalized = n.substring(0, lastComma).replaceAll("[ .,]", "") + "." + after;
            } else {
                normalized = n.replaceAll("[ .,]", "");
            }
        } else if (lastDot > lastComma) {
            String after = n.substring(lastDot + 1).trim();
            if (after.length() == 3 && lastComma < 0 && n.indexOf('.') != lastDot) {
                normalized = n.replaceAll("[ .,]", ""); // "1.234.567" European thousands
            } else {
                normalized = n.substring(0, lastDot).replaceAll("[ .,]", "") + "." + after.replace(" ", "");
            }
        } else {
            normalized = n.replace(" ", "");
        }
        BigDecimal v = new BigDecimal(normalized);
        return (negative ? v.negate() : v);
    }

    public static BigDecimal money(String raw) {
        BigDecimal v = amount(raw);
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("janv", 1), Map.entry("janvier", 1), Map.entry("january", 1),
            Map.entry("feb", 2), Map.entry("fev", 2), Map.entry("fevr", 2), Map.entry("fevrier", 2), Map.entry("february", 2),
            Map.entry("mar", 3), Map.entry("mars", 3), Map.entry("march", 3),
            Map.entry("apr", 4), Map.entry("avr", 4), Map.entry("avril", 4), Map.entry("april", 4),
            Map.entry("may", 5), Map.entry("mai", 5),
            Map.entry("jun", 6), Map.entry("juin", 6), Map.entry("june", 6),
            Map.entry("jul", 7), Map.entry("juil", 7), Map.entry("juillet", 7), Map.entry("july", 7),
            Map.entry("aug", 8), Map.entry("aou", 8), Map.entry("aout", 8), Map.entry("august", 8),
            Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("septembre", 9), Map.entry("september", 9),
            Map.entry("oct", 10), Map.entry("octobre", 10), Map.entry("october", 10),
            Map.entry("nov", 11), Map.entry("novembre", 11), Map.entry("november", 11),
            Map.entry("dec", 12), Map.entry("decembre", 12), Map.entry("december", 12));

    private static final Pattern ISO = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern DMY = Pattern.compile("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})");
    private static final Pattern TEXT_MDY = Pattern.compile("([a-z]+)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})");
    private static final Pattern TEXT_DMY = Pattern.compile("(\\d{1,2})(?:er)?\\s+([a-z]+)\\.?,?\\s+(\\d{4})");

    /** Parse a printed date to ISO. Ambiguous d/m vs m/d with both <= 12 is read as day-first (Canadian/French usage). */
    public static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = stripAccents(raw.trim().toLowerCase(Locale.ROOT));
        try {
            Matcher m = ISO.matcher(s);
            if (m.find()) return LocalDate.of(i(m.group(1)), i(m.group(2)), i(m.group(3)));
            m = DMY.matcher(s);
            if (m.find()) {
                int a = i(m.group(1)), b = i(m.group(2)), y = i(m.group(3));
                return a > 12 ? LocalDate.of(y, b, a) : b > 12 ? LocalDate.of(y, a, b) : LocalDate.of(y, b, a);
            }
            m = TEXT_MDY.matcher(s);
            if (m.find() && MONTHS.containsKey(m.group(1))) return LocalDate.of(i(m.group(3)), MONTHS.get(m.group(1)), i(m.group(2)));
            m = TEXT_DMY.matcher(s);
            if (m.find() && MONTHS.containsKey(m.group(2))) return LocalDate.of(i(m.group(3)), MONTHS.get(m.group(2)), i(m.group(1)));
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (java.time.DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    /** Map a printed tax label (+ optional rate) to a dataset tax code. */
    public static String taxCode(String label, String ratePercent) {
        String l = stripAccents(label == null ? "" : label.toUpperCase(Locale.ROOT));
        BigDecimal rate = percent(ratePercent);
        if (rate == null) {
            Matcher m = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%").matcher(l);
            if (m.find()) rate = percent(m.group(1));
        }
        if (l.contains("HST") || l.contains("TVH")) {
            if (l.contains("GST/HST") || l.contains("TPS/TVH")) {
                return rate != null && rate.compareTo(new BigDecimal("10")) > 0 ? "HST13" : "GST5";
            }
            return "HST13";
        }
        if (l.contains("QST") || l.contains("TVQ")) return "QST9975";
        if (l.contains("PST") || l.contains("TVP") || l.contains("RST")) return "PST7_BC";
        if (l.contains("GST") || l.contains("TPS")) return "GST5";
        if (rate != null) {
            if (rate.compareTo(new BigDecimal("13")) == 0) return "HST13";
            if (rate.compareTo(new BigDecimal("9.975")) == 0) return "QST9975";
            if (rate.compareTo(new BigDecimal("7")) == 0) return "PST7_BC";
            if (rate.compareTo(new BigDecimal("5")) == 0) return "GST5";
        }
        return null;
    }

    /** A percentage as printed: "9,975", "9.975 %", "13%" -> 9.975 / 13. A comma here is always decimal. */
    public static BigDecimal percent(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^0-9.,]", "").replace(',', '.');
        if (s.isEmpty() || s.equals(".")) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Percent as a fraction: "9,975" -> 0.09975. */
    public static BigDecimal rateFraction(String ratePercent) {
        BigDecimal r = percent(ratePercent);
        return r == null ? null : r.movePointLeft(2);
    }

    public static String currency(String raw, String total) {
        String s = ((raw == null ? "" : raw) + " " + (total == null ? "" : total)).toUpperCase(Locale.ROOT);
        if (s.contains("USD") || s.contains("US$") || s.contains("U.S.")) return "USD";
        if (s.contains("EUR") || s.contains("€")) return "EUR";
        return "CAD";
    }

    public static String digits(String s) { return s == null ? null : s.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT); }

    public static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    public static String stripAccents(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static int i(String s) { return Integer.parseInt(s); }
}
