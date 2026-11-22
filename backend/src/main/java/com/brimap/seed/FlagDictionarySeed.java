package com.brimap.seed;

import com.brimap.model.FlagDictionary;
import com.brimap.model.Severity;
import com.brimap.repository.FlagDictionaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.brimap.model.Severity.*;

/** The flag_dictionary table: one source of truth for flag codes, titles and severities (UI + README). */
@Component
@RequiredArgsConstructor
public class FlagDictionarySeed {
    private final FlagDictionaryRepository repo;

    public static final List<FlagDictionary> ENTRIES = List.of(
            e("X01", "Document integrity", "Line arithmetic", WARN, "A line's quantity x unit price does not equal its amount (> $0.01)."),
            e("X02", "Document integrity", "Totals do not add up", WARN, "Lines do not sum to the subtotal, or subtotal + taxes does not equal the total."),
            e("X03", "Document integrity", "Missing or low-confidence field", WARN, "Vendor, invoice number, date or total is missing or the extractor was unsure."),
            e("X04", "Document integrity", "Suspicious dates", WARN, "Invoice dated in the future, due before it was issued, or older than 180 days."),
            e("X05", "Document integrity", "Credit note", INFO, "Negative total or the document says credit note; it offsets the vendor balance."),
            e("V01", "Vendor", "Unknown vendor", BLOCK, "No vendor match by GST number, sender domain or name. Onboard before payment."),
            e("V02", "Vendor", "Vendor not approved", BLOCK, "Vendor is pending review or on hold; Finance Manager approval needed."),
            e("V03", "Vendor", "Tax number mismatch", WARN, "GST/QST number on the invoice differs from the vendor record or is badly formatted."),
            e("V04", "Vendor", "Lookalike vendor", BLOCK, "Name closely resembles another vendor that has a different tax number or bank account."),
            e("V05", "Vendor", "Sender domain mismatch", WARN, "Email sender domain differs from the vendor's AP contact domain."),
            e("V06", "Vendor", "Lookalike sender domain", WARN, "Email came from a domain that resembles another vendor's domain."),
            e("B01", "Bank / fraud", "Remit-to account differs", BLOCK, "Bank account printed on the invoice differs from the vendor's verified account on file."),
            e("B02", "Bank / fraud", "Unverified recent bank change", BLOCK, "Vendor bank details changed recently and were not verified by callback."),
            e("B03", "Bank / fraud", "Bank change by email", WARN, "The vendor's last bank change was requested by email."),
            e("B04", "Bank / fraud", "Shared bank account", WARN, "Another vendor record uses the same bank account."),
            e("D01", "Duplicates", "Duplicate invoice number", BLOCK, "Same vendor and same normalized invoice number as an existing invoice."),
            e("D02", "Duplicates", "Possible duplicate", WARN, "Same vendor, total within 1%, dates within 30 days, invoice number differs by at most 2 characters."),
            e("D03", "Duplicates", "Same document", BLOCK, "Identical file, or same vendor + total + date under a different number."),
            e("D04", "Duplicates", "Already paid by card", BLOCK, "A Brim card transaction for this vendor and amount was posted within 45 days."),
            e("T01", "Tax", "Tax does not match province", WARN, "Charged tax differs from what the ship-to province requires."),
            e("T02", "Tax", "Tax charged when none is due", WARN, "Foreign vendor charged Canadian tax, or an exempt category was taxed."),
            e("T03", "Tax", "GST without registration", WARN, "GST charged but there is no GST number on file or on the invoice; ITC at risk."),
            e("K01", "Contract / PO", "Contract expired", WARN, "Linked contract ended before the invoice date."),
            e("K02", "Contract / PO", "Fixed fee mismatch", WARN, "Subtotal differs from the contract's fixed monthly fee."),
            e("K03", "Contract / PO", "Hourly rate above cap", WARN, "Hourly unit price exceeds the contract's rate cap."),
            e("K04", "Contract / PO", "Fuel surcharge above cap", WARN, "Fuel surcharge percentage exceeds the contract cap."),
            e("K05", "Contract / PO", "Monthly cap exceeded", WARN, "Vendor's invoices this month exceed the contract's not-to-exceed amount."),
            e("P13", "Contract / PO", "PO required", BLOCK, "Vendor requires a purchase order and none is referenced."),
            e("PO1", "Contract / PO", "PO problem", WARN, "Referenced PO not found, closed, or belongs to another vendor."),
            e("P12", "Contract / PO", "Over PO balance", WARN, "Invoice exceeds the PO's remaining balance; Finance Manager step added."),
            e("S01", "Approval gaming", "Possible split invoice", WARN, "Several invoices from one vendor/department, each under a limit, together over it. Routed at the combined amount."),
            e("FX1", "Currency", "Foreign currency", INFO, "Invoice is not in CAD; converted at the invoice-date rate."),
            e("FX2", "Currency", "FX rate fallback", INFO, "No rate for the invoice date; previous available rate used."),
            e("E01", "Email intake", "Attachment missing", WARN, "Email says an invoice is attached but nothing is attached."),
            e("E02", "Email intake", "Payment inquiry", INFO, "Vendor asks about payment status."),
            e("E03", "Email intake", "Statement of account", INFO, "Filed for reconciliation; no bill created."),
            e("E04", "Email intake", "Not an invoice", INFO, "Marketing or unrelated email, ignored.")
    );

    private static FlagDictionary e(String code, String cat, String title, Severity sev, String text) {
        return new FlagDictionary(code, cat, title, sev, text, true);
    }

    /** Saves fresh copies, so an admin's edits never leak into ENTRIES (the defaults shown next to them). */
    public void seed() {
        repo.saveAll(ENTRIES.stream().map(f -> new FlagDictionary(f.getCode(), f.getCategory(), f.getTitle(), f.getSeverity(), f.getExplanation(), true)).toList());
    }

    public static FlagDictionary lookup(String code) {
        return ENTRIES.stream().filter(f -> f.getCode().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown flag code " + code));
    }
}
