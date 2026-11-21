package com.brimap.seed;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny helpers for reading the dataset CSVs. Blank cells become null. */
public final class Csv {
    private Csv() {}

    public static List<Map<String, String>> read(Path file) {
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).get();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8); CSVParser p = CSVParser.parse(r, fmt)) {
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord rec : p) {
                Map<String, String> m = new LinkedHashMap<>();
                for (String h : p.getHeaderNames()) {
                    String v = rec.isMapped(h) && rec.isSet(h) ? rec.get(h) : null;
                    m.put(h, v == null || v.isBlank() ? null : v);
                }
                rows.add(m);
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }

    public static BigDecimal dec(String s) { return s == null ? null : new BigDecimal(s); }

    public static BigDecimal money(String s) { return s == null ? null : new BigDecimal(s).setScale(2, java.math.RoundingMode.HALF_UP); }

    public static Integer integer(String s) { return s == null ? null : Integer.valueOf(s); }

    public static LocalDate date(String s) { return s == null ? null : LocalDate.parse(s.substring(0, 10)); }

    public static LocalDateTime dateTime(String s) { return s == null ? null : LocalDateTime.parse(s); }

    public static boolean yes(String s) { return s != null && (s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("true")); }
}
