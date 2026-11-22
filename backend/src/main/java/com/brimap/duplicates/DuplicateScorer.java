package com.brimap.duplicates;

import com.brimap.model.Invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explainable duplicate score in [0,1]: same vendor 0.30, same normalized invoice # 0.40 (or within 2 characters
 * 0.20), amount within 1% 0.20, dates within 30 days 0.10; an identical file is 1.0. Shown on every D-flag so
 * a judge can see why two bills were matched.
 */
public final class DuplicateScorer {
    private DuplicateScorer() {}

    public static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");
    public static final int DATE_WINDOW_DAYS = 30;

    public record Score(double value, List<String> reasons, boolean sameVendor, boolean sameNumber, int numberDistance,
                        boolean amountClose, boolean dateClose, boolean sameFile) {}

    public static Score score(Invoice a, String aSha, Invoice b, String bSha) {
        List<String> why = new ArrayList<>();
        double s = 0;
        boolean sameFile = aSha != null && aSha.equals(bSha);
        boolean sameVendor = a.getVendorId() != null && a.getVendorId().equals(b.getVendorId());
        if (sameVendor) { s += 0.30; why.add("same vendor"); }
        String na = a.getNormalizedInvoiceNumber(), nb = b.getNormalizedInvoiceNumber();
        boolean sameNumber = na != null && na.equals(nb);
        int dist = InvoiceNumbers.distance(na, nb);
        if (sameNumber) { s += 0.40; why.add("same invoice # " + na); }
        else if (dist <= 2) { s += 0.20; why.add("invoice # differs by " + dist + " character" + (dist == 1 ? "" : "s")); }
        boolean amountClose = amountClose(a.getTotal(), b.getTotal());
        if (amountClose) { s += 0.20; why.add("amount within 1%"); }
        boolean dateClose = a.getInvoiceDate() != null && b.getInvoiceDate() != null
                && Math.abs(ChronoUnit.DAYS.between(a.getInvoiceDate(), b.getInvoiceDate())) <= DATE_WINDOW_DAYS;
        if (dateClose) { s += 0.10; why.add("dated within " + DATE_WINDOW_DAYS + " days"); }
        if (sameFile) { s = 1.0; why.add(0, "identical file (SHA-256)"); }
        return new Score(Math.min(1.0, Math.round(s * 100) / 100.0), why, sameVendor, sameNumber, dist, amountClose, dateClose, sameFile);
    }

    /** Within 1% of the larger amount, same sign (a credit note is never a duplicate of an invoice). */
    public static boolean amountClose(BigDecimal x, BigDecimal y) {
        if (x == null || y == null || x.signum() != y.signum()) return false;
        BigDecimal max = x.abs().max(y.abs());
        if (max.signum() == 0) return true;
        return x.subtract(y).abs().divide(max, 6, RoundingMode.HALF_UP).compareTo(AMOUNT_TOLERANCE) <= 0;
    }

    public static boolean sameTotal(Invoice a, Invoice b) {
        return a.getTotal() != null && b.getTotal() != null && a.getTotal().compareTo(b.getTotal()) == 0;
    }

    public static boolean sameDate(Invoice a, Invoice b) { return Objects.equals(a.getInvoiceDate(), b.getInvoiceDate()) && a.getInvoiceDate() != null; }
}
