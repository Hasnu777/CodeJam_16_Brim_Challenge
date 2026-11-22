package com.brimap.validation.rules;

import com.brimap.model.Contract;
import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.brimap.validation.ValidationRule.money;

/**
 * K05: the vendor's invoices dated in this invoice's calendar month (pre-tax, this one included, rejected and
 * duplicate bills excluded) exceed the contract's not_to_exceed_monthly.
 */
@Component
public class K05MonthlyCapExceeded implements ValidationRule {
    private static final Set<InvoiceStatus> IGNORE = Set.of(InvoiceStatus.REJECTED, InvoiceStatus.DUPLICATE);

    public String code() { return "K05"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Contract c = ctx.contract();
        Invoice i = ctx.invoice();
        if (c == null || c.getNotToExceedMonthly() == null || i.getInvoiceDate() == null || i.getSubtotal() == null || i.getVendorId() == null) {
            return List.of();
        }
        YearMonth ym = YearMonth.from(i.getInvoiceDate());
        List<Invoice> sameMonth = ctx.otherInvoices().stream()
                .filter(o -> i.getVendorId().equals(o.getVendorId()) && o.getInvoiceDate() != null && YearMonth.from(o.getInvoiceDate()).equals(ym)
                        && !IGNORE.contains(o.getStatus()) && o.getSubtotal() != null)
                .toList();
        BigDecimal total = sameMonth.stream().map(Invoice::getSubtotal).reduce(i.getSubtotal(), BigDecimal::add);
        if (total.compareTo(c.getNotToExceedMonthly()) <= 0) return List.of();
        String month = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.CANADA) + " " + ym.getYear();
        return List.of(flag(month + " total for this vendor is " + money(total) + " pre-tax across " + (sameMonth.size() + 1)
                        + " invoices, over contract " + c.getId() + "'s " + money(c.getNotToExceedMonthly()) + " monthly cap by "
                        + money(total.subtract(c.getNotToExceedMonthly())),
                "contractId", c.getId(), "month", ym.toString(), "monthTotal", total, "cap", c.getNotToExceedMonthly(),
                "invoiceIds", sameMonth.stream().map(Invoice::getId).toList()));
    }
}
