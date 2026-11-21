package com.brimap.validation.rules;

import com.brimap.duplicates.DuplicateScorer;
import com.brimap.extraction.VendorMatcher;
import com.brimap.model.CardTransaction;
import com.brimap.model.Invoice;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

import static com.brimap.validation.ValidationRule.money;

/**
 * D04: already paid by Brim card - a card transaction whose merchant fuzzy-matches the vendor, amount (CAD) within
 * 1%, posted within 45 days of the invoice date. AP virtual-card payments of OTHER known invoices (memo
 * "AP payment AP-xxxxx") are excluded: they are how those invoices were paid, not a double payment of this one.
 */
@Component
public class D04PaidByCard implements ValidationRule {
    public static final int WINDOW_DAYS = 45;
    public static final double MERCHANT_SIMILARITY = 0.90;
    private static final Pattern AP_MEMO = Pattern.compile("AP payment (AP-\\d+)");

    public String code() { return "D04"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        if (ctx.vendor() == null || i.getTotalCad() == null || i.getInvoiceDate() == null || i.getTotalCad().signum() <= 0) return List.of();
        String vendorName = ctx.vendor().getLegalName();
        return ctx.cardTransactions().stream()
                .filter(t -> t.getAmountCad() != null && t.getPostedDate() != null)
                .filter(t -> {
                    var m = t.getMemo() == null ? null : AP_MEMO.matcher(t.getMemo());
                    return m == null || !m.find() || m.group(1).equals(i.getId());
                })
                .filter(t -> Math.abs(ChronoUnit.DAYS.between(i.getInvoiceDate(), t.getPostedDate())) <= WINDOW_DAYS)
                .filter(t -> DuplicateScorer.amountClose(t.getAmountCad(), i.getTotalCad()))
                .filter(t -> merchantMatches(t.getMerchantDescriptor(), vendorName))
                .map(t -> flag("Already paid by Brim card " + t.getId() + " on " + t.getPostedDate() + " (" + money(t.getAmountCad()) + " at "
                                + t.getMerchantDescriptor() + ", cardholder " + t.getCardholderUserId() + ")",
                        "cardTransactionId", t.getId(), "postedDate", t.getPostedDate().toString(), "amountCad", t.getAmountCad(),
                        "merchant", t.getMerchantDescriptor(), "cardholder", t.getCardholderUserId(), "source", t.getSource()))
                .toList();
    }

    /** Card descriptors are truncated and uppercase ("CLOUDBARN SOFTWARE INC", "REDSTONE CLOUD HOSTING"). */
    public static boolean merchantMatches(String descriptor, String vendorName) {
        if (descriptor == null || vendorName == null) return false;
        String d = VendorMatcher.normName(descriptor), v = VendorMatcher.normName(vendorName);
        if (d.isEmpty() || v.isEmpty()) return false;
        return v.startsWith(d) || d.startsWith(v) || VendorMatcher.similarity(descriptor, vendorName) >= MERCHANT_SIMILARITY;
    }
}
