package com.brimap.journal;

import com.brimap.model.*;
import com.brimap.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Accounting export: one balanced journal entry per approved bill (expense lines + tax = Accounts Payable)
 * and one per settled payment (Accounts Payable = bank or Brim card, plus any early-payment discount). All in CAD.
 * Positive amounts are debits and negative amounts are credits, so credit notes reverse automatically.
 */
@RestController
@RequestMapping("/api/journal")
@RequiredArgsConstructor
public class JournalController {
    static final String AP = "2100", GST = "1160", QST = "1165", FX = "7020";
    /** Not in the dataset's chart of accounts: placeholders the accounting system maps to its own accounts. */
    static final String BANK = "1010", CARD = "2110", DISCOUNTS = "4910";
    static final Map<String, String> PLACEHOLDER_NAMES = Map.of(
            BANK, "Bank (operating)", CARD, "Brim card payable", DISCOUNTS, "Early-payment discounts", "UNCODED", "Uncoded (fix before posting)");
    /** A bill is booked once it's approved; anything earlier can still change. */
    static final Set<InvoiceStatus> BOOKED = EnumSet.of(InvoiceStatus.APPROVED, InvoiceStatus.SCHEDULED, InvoiceStatus.RELEASED, InvoiceStatus.PAID);

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final InvoiceTaxRepository taxes;
    private final TaxCodeRepository taxCodes;
    private final PaymentRepository payments;
    private final GlAccountRepository glAccounts;
    private final VendorRepository vendors;

    /** One line of a journal entry. amount > 0 is a debit, amount < 0 is a credit. */
    record Row(String entry, LocalDate date, String source, String reference, String vendor, String account, BigDecimal amount, String memo) {}

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('FM','ADM','AUD')")
    @Transactional(readOnly = true)
    public ResponseEntity<String> export(@RequestParam(defaultValue = "false") boolean includeHistory) {
        Map<String, String> vendorNames = vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, Vendor::getLegalName));
        Map<String, TaxCode> codes = taxCodes.findAll().stream().collect(Collectors.toMap(TaxCode::getCode, t -> t));
        Map<String, String> accountNames = new HashMap<>(PLACEHOLDER_NAMES);
        glAccounts.findAll().forEach(g -> accountNames.put(g.getAccountNumber(), g.getName()));
        Map<String, Invoice> byId = new HashMap<>();
        List<Row> rows = new ArrayList<>();

        for (Invoice inv : invoices.findAll()) {
            byId.put(inv.getId(), inv);
            if (inv.isHistorical() && !includeHistory) continue;
            if (!BOOKED.contains(inv.getStatus())) continue;
            rows.addAll(billEntry(inv, codes, vendorName(inv, vendorNames)));
        }
        for (Payment p : payments.findAll()) {
            if (!"SETTLED".equalsIgnoreCase(p.getStatus()) || p.getPaidDate() == null) continue;
            Invoice inv = byId.get(p.getInvoiceId());
            if (inv != null && inv.isHistorical() && !includeHistory) continue;
            rows.addAll(paymentEntry(p, inv == null ? vendorNames.get(p.getVendorId()) : vendorName(inv, vendorNames)));
        }
        rows.sort(Comparator.comparing(Row::date, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Row::entry));

        StringBuilder sb = new StringBuilder("entry,date,source,reference,vendor,account,account_name,debit,credit,memo\n");
        for (Row r : rows) {
            if (r.amount().signum() == 0) continue;
            String debit = r.amount().signum() > 0 ? r.amount().toPlainString() : "";
            String credit = r.amount().signum() < 0 ? r.amount().negate().toPlainString() : "";
            sb.append(c(r.entry())).append(',').append(r.date() == null ? "" : r.date()).append(',').append(r.source()).append(',')
                    .append(c(r.reference())).append(',').append(c(r.vendor())).append(',').append(r.account()).append(',')
                    .append(c(accountNames.getOrDefault(r.account(), ""))).append(',').append(debit).append(',').append(credit).append(',')
                    .append(c(r.memo())).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=journal.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(sb.toString());
    }

    /** Approved bill: Dr expense per line, Dr recoverable tax (1160/1165), Cr 2100 Accounts Payable. */
    private List<Row> billEntry(Invoice inv, Map<String, TaxCode> codes, String vendor) {
        BigDecimal fx = inv.getFxRateToCad() == null ? BigDecimal.ONE : inv.getFxRateToCad();
        BigDecimal total = inv.getTotalCad() != null ? inv.getTotalCad() : cad(inv.getTotal(), fx);
        if (total == null) return List.of();
        String entry = "JE-" + inv.getId();
        String ref = inv.getId() + " / " + inv.getVendorInvoiceNumber();
        List<Row> out = new ArrayList<>();

        List<InvoiceLine> ls = lines.findByInvoiceIdOrderByLineNo(inv.getId());
        String costAccount = ls.stream().map(InvoiceLine::getGlAccount).filter(Objects::nonNull).findFirst().orElse("UNCODED");
        for (InvoiceLine l : ls) {
            if (l.getAmount() == null) continue;
            out.add(new Row(entry, inv.getInvoiceDate(), "BILL", ref, vendor,
                    l.getGlAccount() == null ? "UNCODED" : l.getGlAccount(), cad(l.getAmount(), fx), l.getDescription()));
        }
        if (ls.isEmpty() && inv.getSubtotal() != null) {
            out.add(new Row(entry, inv.getInvoiceDate(), "BILL", ref, vendor, "UNCODED", cad(inv.getSubtotal(), fx), "No lines extracted"));
        }

        List<InvoiceTax> ts = taxes.findByInvoiceId(inv.getId());
        if (!ts.isEmpty()) {
            for (InvoiceTax t : ts) {
                if (t.getAmount() == null || t.getAmount().signum() == 0) continue;
                TaxCode tc = codes.get(t.getTaxCode());
                boolean recoverable = tc != null && "yes".equalsIgnoreCase(tc.getRecoverable()) && tc.getGlAccount() != null && !tc.getGlAccount().isBlank();
                out.add(new Row(entry, inv.getInvoiceDate(), "BILL", ref, vendor, recoverable ? tc.getGlAccount() : costAccount, cad(t.getAmount(), fx),
                        (recoverable ? "Recoverable tax: " : "Non-recoverable tax, part of the cost: ") + t.getLabel()));
            }
        } else {
            // Older bills only carry the two tax totals: GST/HST is recoverable; the second tax is QST in Quebec, otherwise PST (a cost).
            if (inv.getGstHstAmount() != null && inv.getGstHstAmount().signum() != 0) {
                out.add(new Row(entry, inv.getInvoiceDate(), "BILL", ref, vendor, GST, cad(inv.getGstHstAmount(), fx), "Recoverable tax: GST/HST"));
            }
            if (inv.getPstQstAmount() != null && inv.getPstQstAmount().signum() != 0) {
                boolean qc = "QC".equalsIgnoreCase(inv.getShipToProvince());
                out.add(new Row(entry, inv.getInvoiceDate(), "BILL", ref, vendor, qc ? QST : costAccount, cad(inv.getPstQstAmount(), fx),
                        qc ? "Recoverable tax: QST" : "Non-recoverable tax, part of the cost: PST"));
            }
        }

        out.add(new Row(entry, inv.getInvoiceDate(), "BILL", ref, vendor, AP, total.negate(), "Owed to " + vendor));
        BigDecimal diff = sum(out).negate();
        if (diff.signum() != 0) {
            out.add(new Row(entry, inv.getInvoiceDate(), "BILL", ref, vendor, FX, diff, "Rounding / currency conversion difference"));
        }
        return out;
    }

    /** Settled payment: Dr 2100 Accounts Payable, Cr bank (or Brim card), Cr discounts if paid early. */
    private List<Row> paymentEntry(Payment p, String vendor) {
        BigDecimal fx = p.getFxRateToCad() == null ? BigDecimal.ONE : p.getFxRateToCad();
        BigDecimal paid = p.getAmountCad() != null ? p.getAmountCad() : cad(p.getAmount(), fx);
        if (paid == null) return List.of();
        BigDecimal discount = p.getDiscountTaken() == null ? BigDecimal.ZERO : cad(p.getDiscountTaken(), fx);
        String entry = "JE-" + p.getId();
        String ref = p.getId() + " / " + p.getInvoiceId() + (p.getReference() == null ? "" : " / " + p.getReference());
        boolean card = "CARD".equalsIgnoreCase(p.getMethod());
        List<Row> out = new ArrayList<>();
        out.add(new Row(entry, p.getPaidDate(), "PAYMENT", ref, vendor, AP, paid.add(discount), "Bill paid"));
        out.add(new Row(entry, p.getPaidDate(), "PAYMENT", ref, vendor, card ? CARD : BANK, paid.negate(), card ? "Paid by Brim card" : "Paid by " + p.getMethod()));
        if (discount.signum() != 0) {
            out.add(new Row(entry, p.getPaidDate(), "PAYMENT", ref, vendor, DISCOUNTS, discount.negate(), "Early-payment discount taken"));
        }
        return out;
    }

    private static BigDecimal cad(BigDecimal amount, BigDecimal fx) {
        return amount == null ? null : amount.multiply(fx).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<Row> rows) {
        return rows.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String vendorName(Invoice inv, Map<String, String> names) {
        String n = inv.getVendorId() == null ? null : names.get(inv.getVendorId());
        return n != null ? n : inv.getVendorNameRaw();
    }

    private static String c(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}