package com.brimap.validation.rules;

import com.brimap.model.BankChangeRequest;
import com.brimap.model.Invoice;
import com.brimap.model.Vendor;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * B02: the vendor's bank details changed and the change was not verified by callback. Within 30 days of the
 * invoice it is the textbook business-email-compromise pattern; older unverified changes still hold payment.
 */
@Component
public class B02UnverifiedBankChange implements ValidationRule {
    public static final int RECENT_DAYS = 30;
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("MMM d", Locale.CANADA);

    public String code() { return "B02"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Vendor v = ctx.vendor();
        if (v == null) return List.of();
        Invoice i = ctx.invoice();
        return ctx.bankChanges().stream()
                .filter(c -> v.getId().equals(c.getVendorId()) && "PENDING".equals(c.getStatus()))
                .map(c -> {
                    long days = ChronoUnit.DAYS.between(c.getChangedAt(), i.getInvoiceDate() != null ? i.getInvoiceDate() : ctx.today());
                    boolean recent = Math.abs(days) <= RECENT_DAYS;
                    boolean paysToIt = i.getRemitAccount() != null && c.getNewAccountNumber() != null && i.getRemitAccount().equals(c.getNewAccountNumber());
                    String msg = v.getLegalName() + "'s bank account was switched to ..." + c.getNewAccountLast4() + " on " + D.format(c.getChangedAt())
                            + " by " + channel(c) + " and never verified by callback"
                            + (!recent ? "" : days == 0 ? " (the same day this invoice was issued)"
                                    : days > 0 ? " (" + days + " days before this invoice)" : " (" + (-days) + " days after this invoice)") + "."
                            + (paysToIt ? " This invoice pays to that account." : "")
                            + " Payment is held until a Finance Manager verifies the change.";
                    return flag(msg, "bankChangeRequestId", c.getId(), "changedAt", c.getChangedAt().toString(), "channel", c.getRequestChannel(),
                            "oldLast4", c.getOldAccountLast4(), "newLast4", c.getNewAccountLast4(), "daysBeforeInvoice", days,
                            "invoicePaysToNewAccount", paysToIt);
                }).toList();
    }

    static String channel(BankChangeRequest c) {
        return switch (c.getRequestChannel() == null ? "" : c.getRequestChannel()) {
            case "email_request" -> "email request";
            case "vendor_portal" -> "vendor portal";
            case "phone_callback_verified" -> "phone (callback verified)";
            default -> c.getRequestChannel();
        };
    }
}
