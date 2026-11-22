package com.brimap.validation;

import com.brimap.duplicates.InvoiceNumbers;
import com.brimap.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Rows copied from dataset_given/data/*.csv, used to build rule contexts by hand. */
public final class Fixtures {
    private Fixtures() {}

    public static final LocalDate TODAY = LocalDate.of(2026, 11, 2); // dataset as-of date

    public static BigDecimal bd(String s) { return new BigDecimal(s); }

    public static Vendor vendor(String id, String name, String category, String gst, String country, String status, boolean requiresPo, String email) {
        Vendor v = new Vendor();
        v.setId(id); v.setLegalName(name); v.setCategory(category); v.setGstHstNumber(gst); v.setCountry(country);
        v.setStatus(status); v.setRequiresPo(requiresPo); v.setApContactEmail(email); v.setPreferredPaymentMethod("EFT");
        return v;
    }

    // vendors.csv
    public static Vendor polar() { return vendor("V001", "Polar Freight Lines Ltd.", "Freight & Carriers", "995133220RT0001", "CA", "active", false, "ar@polarfreight.example"); }
    public static Vendor polarDup() { return vendor("V051", "Polar Freight Lines Limited", "Freight & Carriers", "420074708RT0001", "CA", "active", false, "ar@polarfreight.example"); }
    public static Vendor cascadia() { return vendor("V004", "Cascadia Cartage Ltd.", "Freight & Carriers", "669086667RT0001", "CA", "active", false, "ar@cascadiacartage.example"); }
    public static Vendor laurentian() { return vendor("V005", "Laurentian Logistics Inc.", "Freight & Carriers", "790628686RT0001", "CA", "active", false, "ar@laurentianlogistics.example"); }
    public static Vendor brightline() { return vendor("V013", "Brightline Safety Supply", "Safety Supplies", "612797763RT0001", "CA", "active", true, "ar@brightlinesafety.example"); }
    public static Vendor cloudbarn() { return vendor("V015", "Cloudbarn Software Inc.", "Software & SaaS", "422479028RT0001", "CA", "active", false, "ar@cloudbarnsoftware.example"); }
    public static Vendor sparkgrid() { return vendor("V018", "Sparkgrid CRM Inc.", "Software & SaaS", null, "US", "active", false, "ar@sparkgridcrm.example"); }
    public static Vendor harlow() { return vendor("V026", "Harlow & Stern LLP", "Legal", "134920677RT0001", "CA", "active", false, "ar@harlow.example"); }
    public static Vendor beaumont() { return vendor("V027", "Beaumont Riviere S.E.N.C.R.L.", "Legal", "941999763RT0001", "CA", "active", false, "ar@beaumontriviere.example"); }
    public static Vendor orion() { return vendor("V029", "Orion Strategy Partners", "Consulting", "483236076RT0001", "CA", "active", false, "ar@orionstrategy.example"); }
    public static Vendor guardian() { return vendor("V034", "Guardian Peak Insurance Brokers", "Insurance", "470676530RT0001", "CA", "active", false, "ar@guardianpeak.example"); }
    public static Vendor fixwell() { return vendor("V043", "Fixwell Facility Services", "Repairs & Maintenance", "880851885RT0001", "CA", "active", true, "ar@fixwellfacility.example"); }
    public static Vendor torque() { return vendor("V044", "Torque Industrial Maintenance", "Repairs & Maintenance", "283027511RT0001", "CA", "active", true, "ar@torqueindustrial.example"); }
    public static Vendor harlowLookalike() { return vendor("V052", "Harlow and Stern LLP", "Legal", "503486760RT0001", "CA", "active", false, "billing@harlowandstern-llp.example"); }
    public static Vendor riverbend() { return vendor("V054", "Riverbend Catering Co.", "Events & Catering", null, "CA", "pending_review", true, "ar@riverbendcatering.example"); }

    public static VendorBankAccount account(String vendorId, int version, String acct, String status, boolean verified, String bcId) {
        VendorBankAccount a = new VendorBankAccount();
        a.setVendorId(vendorId); a.setVersion(version); a.setAccountNumber(acct);
        a.setLast4(acct.substring(acct.length() - 4)); a.setStatus(status); a.setVerified(verified); a.setBankChangeRequestId(bcId);
        return a;
    }

    /** Cascadia: BC-009 2026-10-27 email_request, not verified, ...7504 -> ...9679 (already in the vendor master). */
    public static List<VendorBankAccount> cascadiaAccounts() {
        VendorBankAccount old = account("V004", 1, "7504", "SUPERSEDED", true, null);
        return List.of(old, account("V004", 2, "953389679", "ACTIVE", false, "BC-009"));
    }

    public static BankChangeRequest bc009() {
        BankChangeRequest b = new BankChangeRequest();
        b.setId("BC-009"); b.setVendorId("V004"); b.setChangedAt(LocalDate.of(2026, 10, 27)); b.setRequestedBy("U005");
        b.setRequestChannel("email_request"); b.setVerifiedByCallback(false); b.setOldAccountLast4("7504"); b.setNewAccountLast4("9679");
        b.setNewAccountNumber("953389679"); b.setStatus("PENDING"); b.setAppliedBeforeVerification(true);
        return b;
    }

    public static Invoice invoice(String id, String vendorId, String number, String date, String subtotal, String total) {
        Invoice i = new Invoice();
        i.setId(id); i.setVendorId(vendorId); i.setVendorInvoiceNumber(number); i.setNormalizedInvoiceNumber(InvoiceNumbers.normalize(number));
        i.setInvoiceDate(date == null ? null : LocalDate.parse(date)); i.setCurrency("CAD");
        i.setSubtotal(subtotal == null ? null : bd(subtotal)); i.setTotal(total == null ? null : bd(total));
        i.setTotalCad(i.getTotal()); i.setFxRateToCad(BigDecimal.ONE); i.setStatus(InvoiceStatus.EXTRACTED);
        i.setVendorNameRaw(vendorId);
        return i;
    }

    public static Invoice history(String id, String vendorId, String number, String date, String subtotal, String total, InvoiceStatus status) {
        Invoice i = invoice(id, vendorId, number, date, subtotal, total);
        i.setHistorical(true);
        i.setStatus(status);
        return i;
    }

    public static InvoiceLine line(int no, String desc, String qty, String unit, String amount, String type) {
        InvoiceLine l = new InvoiceLine();
        l.setLineNo(no); l.setDescription(desc); l.setQuantity(bd(qty)); l.setUnitPrice(bd(unit)); l.setAmount(bd(amount)); l.setLineType(type);
        return l;
    }

    public static InvoiceTax tax(String code, String amount) {
        InvoiceTax t = new InvoiceTax();
        t.setTaxCode(code); t.setLabel(code); t.setAmount(bd(amount));
        return t;
    }

    public static List<TaxCode> taxCodes() {
        List<TaxCode> out = new ArrayList<>();
        for (String[] r : new String[][]{{"HST13", "0.13", "all"}, {"GST5", "0.05", "all"}, {"PST7_BC", "0.07", "goods"},
                {"QST9975", "0.09975", "all"}, {"EXEMPT", "0", "all"}, {"FOREIGN", "0", "all"}}) {
            TaxCode t = new TaxCode();
            t.setCode(r[0]); t.setRate(bd(r[1])); t.setAppliesTo(r[2]);
            out.add(t);
        }
        return out;
    }

    public static Contract contract(String id, String vendorId, String type, String monthlyFee, String hourlyCap, String fuelCap, String nte,
                                    String start, String end, String status) {
        Contract c = new Contract();
        c.setId(id); c.setVendorId(vendorId); c.setBillingType(type); c.setDescription(type); c.setCurrency("CAD");
        c.setMonthlyFee(monthlyFee == null ? null : bd(monthlyFee)); c.setHourlyRateCap(hourlyCap == null ? null : bd(hourlyCap));
        c.setFuelSurchargeCapPct(fuelCap == null ? null : bd(fuelCap)); c.setNotToExceedMonthly(nte == null ? null : bd(nte));
        c.setStartDate(LocalDate.parse(start)); c.setEndDate(LocalDate.parse(end)); c.setStatus(status); c.setAutoRenew(true);
        return c;
    }

    /** A context with today's date, the dataset tax codes and the default approval tiers. */
    public static InvoiceContext.InvoiceContextBuilder ctx(Invoice inv, Vendor vendor) {
        var b = InvoiceContext.builder().invoice(inv).vendor(vendor).today(TODAY)
                .approvalThreshold(bd("10000")).approvalThreshold(bd("50000")).approvalThreshold(bd("250000"));
        if (vendor != null) b.anyVendor(vendor);
        taxCodes().forEach(t -> b.taxCode(t.getCode(), t));
        return b;
    }
}
