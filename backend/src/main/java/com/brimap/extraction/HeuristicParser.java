package com.brimap.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline fallback: label-based regex parsing of a text layer (pdftotext -raw or OCR), English and French.
 * Used when no LLM is configured or the LLM call fails. Confidence is capped at 0.7 so every field is
 * highlighted for review.
 */
public final class HeuristicParser {
    private HeuristicParser() {}

    private static final String MONEY = "\\(?-?\\$?-?\\s?\\d{1,3}(?:[ ,\\u00a0\\u202f]\\d{3})*(?:[.,]\\d{2})\\)?\\s?\\$?";
    private static final Pattern MONEY_AT_END = Pattern.compile("(" + MONEY + ")\\s*$");
    private static final Pattern LINE = Pattern.compile("^(.+?)\\s+(-?\\d+(?:[.,]\\d+)?)\\s+(" + MONEY + ")\\s+(" + MONEY + ")\\s*$");
    // OST / 0ST / 6ST / H5T are common OCR misreads of QST / QST / GST / HST
    private static final Pattern TAX = Pattern.compile("^((?:GST|HST|QST|PST|TPS|TVQ|TVH|RST|OST|0ST|6ST|H5T)[^\\d(]*)\\(?\\s*(\\d+(?:[.,]\\d+)?)\\s*%\\s*\\)?\\s+(" + MONEY + ")\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GST = Pattern.compile("(\\d{9})\\s?RT\\s?(\\d{4})");
    private static final Pattern QST = Pattern.compile("(\\d{10})\\s?TQ\\s?(\\d{4})");
    private static final Pattern REMIT = Pattern.compile("Inst\\.?\\s*(\\d{3})\\s+Transit\\s+(\\d{5})\\s+Acc?t\\.?\\s+(\\d{5,12})", Pattern.CASE_INSENSITIVE);
    private static final String HARBORLINE_GST = "100000001RT0001";

    public static ExtractedInvoice parse(String text) {
        if (text == null || text.isBlank()) return null;
        List<String> lines = text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        String upper = text.toUpperCase(Locale.ROOT);

        String vendorName = lines.isEmpty() ? "" : lines.get(0);
        String vendorAddress = lines.size() > 2 ? lines.get(1) + ", " + lines.get(2) : "";
        String gst = "", qst = "";
        Matcher g = GST.matcher(text);
        while (g.find()) {
            String v = g.group(1) + "RT" + g.group(2);
            if (!v.equals(HARBORLINE_GST)) { gst = v; break; }
        }
        Matcher q = QST.matcher(text);
        if (q.find()) qst = q.group(1) + "TQ" + q.group(2);

        String invNo = label(lines, "Invoice Number", "Invoice #", "Invoice No.", "Invoice No", "INV NO", "Facture n°", "Facture no", "No. de facture", "Credit note #", "Invoice");
        String date = label(lines, "Issued", "Invoice date", "Date de facture", "DATE", "Date");
        String due = label(lines, "Due by", "Due date", "Payment due", "DUE", "Échéance", "Echeance", "Date d'échéance");
        String terms = label(lines, "Payment terms", "TERMS", "Terms", "Conditions");
        String po = label(lines, "PO REF", "Your PO", "PO #", "Customer PO", "Bon de commande", "PO");

        String billToName = "", billToAddress = "";
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).toLowerCase(Locale.ROOT);
            if (l.equals("bill to") || l.startsWith("facturer") || l.equals("sold to") || l.equals("ship to")) {
                if (i + 1 < lines.size()) billToName = lines.get(i + 1);
                if (i + 2 < lines.size()) billToAddress = lines.get(i + 2);
                break;
            }
        }

        List<ExtractedInvoice.Line> items = new ArrayList<>();
        List<ExtractedInvoice.Tax> taxes = new ArrayList<>();
        String subtotal = "", total = "", currency = upper.contains("TOTAL USD") || upper.contains("USD") ? "USD" : "CAD";
        boolean inItems = false;
        for (String l : lines) {
            String lu = l.toUpperCase(Locale.ROOT);
            if (lu.startsWith("DESCRIPTION")) { inItems = true; continue; }
            if (lu.startsWith("SUBTOTAL") || lu.startsWith("SOUS-TOTAL") || lu.startsWith("SUB-TOTAL")) {
                inItems = false;
                subtotal = moneyAtEnd(l);
                continue;
            }
            if (lu.startsWith("TOTAL")) { total = moneyAtEnd(l); inItems = false; continue; }
            Matcher t = TAX.matcher(l);
            if (!inItems && t.matches()) {
                String label = t.group(1).trim().toUpperCase(Locale.ROOT)
                        .replaceFirst("^(OST|0ST)", "QST").replaceFirst("^6ST", "GST").replaceFirst("^H5T", "HST");
                taxes.add(new ExtractedInvoice.Tax(label + " (" + t.group(2) + "%)", t.group(2), t.group(3).trim()));
                continue;
            }
            if (inItems) {
                Matcher m = LINE.matcher(l);
                if (m.matches()) items.add(new ExtractedInvoice.Line(m.group(1).trim(), m.group(2), m.group(3).trim(), m.group(4).trim()));
            }
        }
        String ri = "", rt = "", ra = "";
        Matcher r = REMIT.matcher(text);
        if (r.find()) { ri = r.group(1); rt = r.group(2); ra = r.group(3); }
        String docType = upper.contains("CREDIT NOTE") || upper.contains("NOTE DE CRÉDIT") || upper.contains("NOTE DE CREDIT") ? "credit_note"
                : upper.contains("STATEMENT OF ACCOUNT") ? "statement" : "invoice";
        String lang = upper.contains("FACTURE") || upper.contains("TPS") ? "fr" : "en";
        double c = 0.7;
        var conf = new ExtractedInvoice.Confidence(vendorName.isEmpty() ? 0 : 0.6, invNo.isEmpty() ? 0 : c, date.isEmpty() ? 0 : c,
                due.isEmpty() ? 0 : c, total.isEmpty() ? 0 : c, items.isEmpty() ? 0 : 0.5, taxes.isEmpty() ? 0.5 : c);
        if (invNo.isEmpty() && total.isEmpty()) return null; // nothing usable: caller makes a manual-entry draft
        return new ExtractedInvoice(docType, vendorName, vendorAddress, gst, qst, invNo, date, due, terms, currency, billToName,
                billToAddress, po, items, subtotal, taxes, total, ri, rt, ra, lang, conf, "Parsed offline by regex fallback");
    }

    private static String moneyAtEnd(String l) {
        Matcher m = MONEY_AT_END.matcher(l);
        return m.find() ? m.group(1).trim() : "";
    }

    /** Value after "Label:" or "LABEL :" on the same line. Labels are tried in order. */
    private static String label(List<String> lines, String... labels) {
        for (String lab : labels) {
            Pattern p = Pattern.compile("^" + Pattern.quote(lab) + "\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
            for (String l : lines) {
                Matcher m = p.matcher(l);
                if (m.matches()) return m.group(1).trim();
            }
        }
        return "";
    }
}
