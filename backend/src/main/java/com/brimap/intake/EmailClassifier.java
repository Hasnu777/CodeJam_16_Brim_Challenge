package com.brimap.intake;

import com.brimap.extraction.InvoiceNormalizer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword rules first (cheap, explainable, deterministic); the caller may use the LLM as a tiebreaker only when
 * no rule matched. Pure function, unit-tested against the four no-attachment emails in the dataset.
 */
public final class EmailClassifier {
    private EmailClassifier() {}

    public enum Kind { INVOICE, NO_ATTACHMENT, PAYMENT_INQUIRY, STATEMENT, NOT_INVOICE, UNKNOWN }

    public record Result(Kind kind, String code, String reason, String referencedInvoiceNumber) {}

    private static final Pattern INQUIRY = Pattern.compile(
            "(where is (the |my |our )?payment|not (yet )?(seen|received) (the |a |any )?payment|payment status|status of (the |our )?payment"
                    + "|when will (we|i) be paid|overdue|past due|remittance advice\\?|paiement (en retard|non re[cç]u)|o[uù] en est le paiement)");
    private static final Pattern STATEMENT = Pattern.compile("(statement of account|account statement|relev[eé] de compte|[eé]tat de compte)");
    private static final Pattern INVOICE_WORD = Pattern.compile("\\b(invoice|invoices|facture|factures|bill)\\b");
    private static final Pattern ATTACH_WORD = Pattern.compile("(attach|enclosed|ci-joint|ci-jointe|en pi[eè]ce jointe|find our|please find)");
    private static final Pattern MARKETING = Pattern.compile("(\\bsale\\b|% off|unsubscribe|newsletter|webinar|promo|shop now|discount code|new arrivals|save up to)");
    private static final Pattern REF_NUMBER = Pattern.compile("(?:invoice|facture|inv)\\s*(?:no\\.?|number|#|n°)?\\s*:?\\s*([A-Z0-9][A-Z0-9-]{1,20})", Pattern.CASE_INSENSITIVE);

    public static Result classify(String subject, String body, List<String> attachments) {
        String s = InvoiceNormalizer.stripAccents(((subject == null ? "" : subject) + "\n" + (body == null ? "" : body)).toLowerCase(Locale.ROOT));
        boolean hasAttachment = attachments != null && attachments.stream().anyMatch(a -> a != null && !a.isBlank());
        String ref = referencedNumber(subject + " " + body);

        if (hasAttachment) {
            if (STATEMENT.matcher(s).find() && !INVOICE_WORD.matcher(subject == null ? "" : subject.toLowerCase(Locale.ROOT)).find()) {
                return new Result(Kind.STATEMENT, "E03", "Statement of account with attachment: filed for reconciliation, no bill created", null);
            }
            // A brochure or flyer attached to a promo email is not a bill. Any mention of an invoice keeps it an invoice;
            // documents that slip through are caught after extraction (DocumentKind).
            if (MARKETING.matcher(s).find() && !INVOICE_WORD.matcher(s).find()) {
                return new Result(Kind.NOT_INVOICE, "E04", "Marketing email with attachment (no invoice mentioned): ignored, no bill created", null);
            }
            return new Result(Kind.INVOICE, null, attachments.size() + " attachment(s): creating draft bill(s)", ref);
        }
        if (INQUIRY.matcher(s).find()) {
            return new Result(Kind.PAYMENT_INQUIRY, "E02", "Vendor is asking about payment" + (ref == null ? "" : " for invoice " + ref), ref);
        }
        if (STATEMENT.matcher(s).find()) {
            return new Result(Kind.STATEMENT, "E03", "Statement of account: filed for reconciliation, no bill created", null);
        }
        if (INVOICE_WORD.matcher(s).find() && ATTACH_WORD.matcher(s).find()) {
            return new Result(Kind.NO_ATTACHMENT, "E01", "Says an invoice is attached but nothing is attached. Reply to the sender and ask them to resend.", ref);
        }
        if (MARKETING.matcher(s).find()) {
            return new Result(Kind.NOT_INVOICE, "E04", "Marketing email (no invoice, no attachment): ignored", null);
        }
        return new Result(Kind.UNKNOWN, null, "No keyword rule matched", ref);
    }

    static String referencedNumber(String text) {
        if (text == null) return null;
        Matcher m = REF_NUMBER.matcher(text);
        while (m.find()) {
            String v = m.group(1);
            if (v.chars().anyMatch(Character::isDigit)) return v;
        }
        return null;
    }
}
