package com.brimap.extraction;

import com.brimap.model.Vendor;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

import java.util.*;

/**
 * Match an extracted supplier to the vendor master: GST number first (strongest), then sender email domain,
 * then fuzzy legal name. Pure function over the vendor list; unit-tested.
 */
public final class VendorMatcher {
    private VendorMatcher() {}

    public static final double NAME_THRESHOLD = 0.93;
    private static final JaroWinklerSimilarity JW = new JaroWinklerSimilarity();

    public record Match(String vendorId, String method, double score, String detail) {
        public static Match none(String detail) { return new Match(null, "NONE", 0, detail); }
    }

    public static Match match(List<Vendor> vendors, String gstOnInvoice, String senderEmail, String nameOnInvoice) {
        String gst = canonicalGst(gstOnInvoice);
        if (gst != null) {
            for (Vendor v : vendors) {
                if (gst.equals(canonicalGst(v.getGstHstNumber()))) return new Match(v.getId(), "GST", 1.0, "GST/HST number " + gst);
            }
        }
        String domain = domain(senderEmail);
        if (domain != null && !domain.endsWith("harborline-logistics.example")) {
            List<Vendor> byDomain = vendors.stream().filter(v -> domain.equals(v.emailDomain())).toList();
            if (byDomain.size() == 1) return new Match(byDomain.get(0).getId(), "DOMAIN", 0.9, "Sender domain " + domain);
            if (byDomain.size() > 1 && nameOnInvoice != null) {
                // Several vendor records share the domain (V001/V051): pick the closest name.
                Vendor best = byDomain.stream().max(Comparator.comparingDouble(v -> similarity(v.getLegalName(), nameOnInvoice))).get();
                return new Match(best.getId(), "DOMAIN", 0.85, "Sender domain " + domain + " shared by "
                        + byDomain.stream().map(Vendor::getId).toList() + "; closest name");
            }
        }
        if (nameOnInvoice != null && !nameOnInvoice.isBlank()) {
            Vendor best = null;
            double bestScore = 0;
            for (Vendor v : vendors) {
                double s = similarity(v.getLegalName(), nameOnInvoice);
                if (s > bestScore) { bestScore = s; best = v; }
            }
            if (best != null && bestScore >= NAME_THRESHOLD) {
                return new Match(best.getId(), "NAME", bestScore, String.format("Name similarity %.2f to %s", bestScore, best.getLegalName()));
            }
            return Match.none(best == null ? "No vendors" : String.format("Closest name %s at %.2f (< %.2f)", best.getLegalName(), bestScore, NAME_THRESHOLD));
        }
        return Match.none("No GST number, sender or name to match");
    }

    /** Normalized name similarity (Jaro-Winkler on lowercase, punctuation-free, legal-suffix-free names). */
    public static double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        return JW.apply(normName(a), normName(b));
    }

    public static String normName(String s) {
        String n = InvoiceNormalizer.stripAccents(s.toLowerCase(Locale.ROOT)).replace("&", " and ");
        n = n.replaceAll("[^a-z0-9 ]", " ");
        n = n.replaceAll("\\b(inc|incorporated|ltd|limited|llp|llc|corp|corporation|co|company|s e n c r l|senc|ltee)\\b", " ");
        return n.replaceAll("\\s+", " ").trim();
    }

    /** "669086667 RT 0001" -> "669086667RT0001"; returns null if it doesn't look like a BN. */
    public static String canonicalGst(String s) {
        if (s == null) return null;
        String d = s.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
        return d.matches("\\d{9}RT\\d{4}") ? d : (d.matches("\\d{9}") ? d + "RT0001" : null);
    }

    public static String domain(String email) {
        if (email == null || !email.contains("@")) return null;
        return email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT).trim();
    }
}
