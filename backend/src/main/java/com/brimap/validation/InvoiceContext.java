package com.brimap.validation;

import com.brimap.model.*;
import lombok.Builder;
import lombok.Singular;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything a validation rule may look at, loaded once. Rules are pure functions of this object, so each rule
 * is unit-tested by building a context by hand from a dataset example (no database).
 *
 * @param otherInvoices every invoice except this one (history + current batch), for duplicates, splits, caps
 * @param documentShaByInvoice sha256 of each invoice's stored document (D03)
 * @param poConsumed amount already billed against {@code po} by other non-rejected invoices (P12)
 */
@Builder(toBuilder = true)
public record InvoiceContext(
        Invoice invoice,
        @Singular List<InvoiceLine> lines,
        @Singular("tax") List<InvoiceTax> taxes,
        Vendor vendor,
        @Singular("anyVendor") List<Vendor> vendors,
        @Singular("bankAccount") List<VendorBankAccount> bankAccounts,
        @Singular List<BankChangeRequest> bankChanges,
        @Singular List<Contract> contracts,
        PurchaseOrder po,
        BigDecimal poConsumed,
        @Singular List<Invoice> otherInvoices,
        @Singular("shaOf") Map<String, String> documentShaByInvoice,
        String documentSha,
        @Singular List<CardTransaction> cardTransactions,
        @Singular Map<String, TaxCode> taxCodes,
        LocalDate today,
        @Singular List<BigDecimal> approvalThresholds) {

    public Contract contract() {
        if (invoice.getContractId() == null) return null;
        return contracts.stream().filter(c -> c.getId().equals(invoice.getContractId())).findFirst().orElse(null);
    }

    /** The vendor's account currently marked ACTIVE (may be unverified). */
    public VendorBankAccount activeAccount(String vendorId) {
        return bankAccounts.stream().filter(a -> vendorId.equals(a.getVendorId()) && "ACTIVE".equals(a.getStatus())).findFirst().orElse(null);
    }

    /** The newest version that was verified (the account we trust). */
    public VendorBankAccount lastVerifiedAccount(String vendorId) {
        return bankAccounts.stream().filter(a -> vendorId.equals(a.getVendorId()) && a.isVerified()
                        && ("ACTIVE".equals(a.getStatus()) || "SUPERSEDED".equals(a.getStatus())))
                .max(java.util.Comparator.comparingInt(VendorBankAccount::getVersion)).orElse(null);
    }

    public BigDecimal rate(String taxCode) {
        TaxCode t = taxCodes.get(taxCode);
        return t == null ? BigDecimal.ZERO : t.getRate();
    }
}
