package com.brimap.extraction;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * What the LLM (or the fallback parser) read off the document. This is UNTRUSTED input: amounts and dates are
 * strings exactly as printed, and deterministic Java (InvoiceNormalizer) turns them into BigDecimal/LocalDate.
 * The JSON schema sent to the model is derived from this record (structured outputs).
 */
public record ExtractedInvoice(
        @JsonPropertyDescription("invoice, credit_note, statement, quote, or other. Use credit_note if the document says credit note / note de credit or totals are negative.")
        String documentType,
        @JsonPropertyDescription("Legal name of the supplier who issued the document, exactly as printed.")
        String vendorName,
        @JsonPropertyDescription("Supplier address as printed, one line.")
        String vendorAddress,
        @JsonPropertyDescription("Supplier GST/HST (TPS/TVH) registration number as printed, e.g. 123456789RT0001. Empty string if none.")
        String vendorGstNumber,
        @JsonPropertyDescription("Supplier QST (TVQ) number as printed, e.g. 1234567890TQ0001. Empty string if none.")
        String vendorQstNumber,
        @JsonPropertyDescription("The supplier's invoice / credit note number exactly as printed (keep prefixes and leading zeros).")
        String invoiceNumber,
        @JsonPropertyDescription("Issue date exactly as printed.")
        String invoiceDate,
        @JsonPropertyDescription("Due date exactly as printed. Empty string if none.")
        String dueDate,
        @JsonPropertyDescription("Payment terms as printed, e.g. Net 30, Due on receipt.")
        String paymentTerms,
        @JsonPropertyDescription("ISO currency code of the totals: CAD or USD (look for 'Total USD', 'US$'). Default CAD for Canadian suppliers.")
        String currency,
        @JsonPropertyDescription("Customer (bill-to) name.")
        String billToName,
        @JsonPropertyDescription("Customer bill-to / ship-to address as printed, one line.")
        String billToAddress,
        @JsonPropertyDescription("Purchase order number referenced on the document. Empty string if blank or absent.")
        String poNumber,
        @JsonPropertyDescription("Every line item, in order.")
        List<Line> lines,
        @JsonPropertyDescription("Subtotal before tax exactly as printed (keep the sign, symbols and number format).")
        String subtotal,
        @JsonPropertyDescription("Each tax line printed on the document (GST, HST, QST, PST, TPS, TVQ, TVH...).")
        List<Tax> taxes,
        @JsonPropertyDescription("Grand total exactly as printed.")
        String total,
        @JsonPropertyDescription("Remit-to bank institution number (3 digits). Empty string if none.")
        String remitInstitution,
        @JsonPropertyDescription("Remit-to transit number (5 digits). Empty string if none.")
        String remitTransit,
        @JsonPropertyDescription("Remit-to account number. Empty string if none.")
        String remitAccount,
        @JsonPropertyDescription("Document language: en or fr.")
        String language,
        @JsonPropertyDescription("Your confidence 0.0-1.0 for each key field.")
        Confidence confidence,
        @JsonPropertyDescription("Anything unusual: illegible parts, handwritten changes, numbers that look inconsistent. Empty string if nothing.")
        String notes) {

    public record Line(
            @JsonPropertyDescription("Line description as printed.") String description,
            @JsonPropertyDescription("Quantity as printed.") String quantity,
            @JsonPropertyDescription("Unit price as printed.") String unitPrice,
            @JsonPropertyDescription("Line amount as printed.") String amount) {}

    public record Tax(
            @JsonPropertyDescription("Tax label as printed, e.g. 'TPS (5 %)' or 'HST (13%)'.") String label,
            @JsonPropertyDescription("Rate as printed, e.g. '5', '9,975', '13'. Empty string if not printed.") String ratePercent,
            @JsonPropertyDescription("Tax amount as printed.") String amount) {}

    public record Confidence(double vendorName, double invoiceNumber, double invoiceDate, double dueDate,
                             double total, double lines, double taxes) {}
}
