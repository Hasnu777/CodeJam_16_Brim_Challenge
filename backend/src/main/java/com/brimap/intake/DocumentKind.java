package com.brimap.intake;

import java.util.Locale;

/**
 * Is an extracted document something AP pays? Only invoices and credit notes go to the clerk's review queue.
 * A statement, quote or anything else the reader recognises as "not an invoice" is filed as REJECTED with the
 * reason in the audit log (action NOT_AN_INVOICE); the document stays viewable from the invoice and the inbox.
 * An empty or missing type counts as an invoice: an unreadable scan may still be a real bill (manual entry).
 */
public final class DocumentKind {
    private DocumentKind() {}

    /** Null when the document belongs in the review queue, otherwise its normalized non-invoice type. */
    public static String nonInvoiceType(String documentType) {
        if (documentType == null || documentType.isBlank()) return null;
        String t = documentType.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (t) {
            case "invoice", "credit_note", "creditnote", "facture", "note_de_credit" -> null;
            default -> t;
        };
    }

    public static boolean isStatement(String nonInvoiceType) {
        return nonInvoiceType != null && nonInvoiceType.contains("statement");
    }
}
