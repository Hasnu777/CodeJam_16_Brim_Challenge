package com.brimap.validation;

import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.validation.rules.*;
import org.junit.jupiter.api.Test;

import static com.brimap.validation.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class VendorAndBankRulesTest {

    @Test
    void v01_unknownVendor_blueHeron4443() {
        Invoice inv = invoice("AP-T1", null, "4443", "2026-10-20", "1340.70", "1407.74");
        inv.setVendorNameRaw("Blue Heron Couriers");
        inv.setGstNumberOnInvoice("324978405RT0001");
        var flags = new V01UnknownVendor().check(ctx(inv, null).build());
        assertThat(flags).singleElement().satisfies(f -> {
            assertThat(f.severity().name()).isEqualTo("BLOCK");
            assertThat(f.message()).startsWith("Blue Heron Couriers is not in the vendor master");
        });
    }

    @Test
    void v02_pendingVendor_riverbend1739() {
        Invoice inv = invoice("AP-T2", "V054", "1739", "2026-10-26", "2860.00", "2860.00");
        var flags = new V02VendorNotApproved().check(ctx(inv, riverbend()).build());
        assertThat(flags).singleElement().satisfies(f -> assertThat(f.message()).contains("Riverbend Catering Co. (V054) is pending review"));
        assertThat(new V02VendorNotApproved().check(ctx(inv, polar()).build())).isEmpty();
    }

    @Test
    void v03_gstOnInvoiceDiffersFromRecord_polarVsDuplicateRecordNumber() {
        // V051's GST number printed on a bill matched to V001
        Invoice inv = invoice("AP-T3", "V001", "000963", "2026-10-25", "100.00", "113.00");
        inv.setGstNumberOnInvoice("420074708RT0001");
        assertThat(new V03TaxNumberMismatch().check(ctx(inv, polar()).build()))
                .singleElement().satisfies(f -> assertThat(f.message()).contains("differs from Polar Freight Lines Ltd.'s record (995133220RT0001)"));
        inv.setGstNumberOnInvoice("12345RT1");
        assertThat(new V03TaxNumberMismatch().check(ctx(inv, polar()).build())).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("not in the valid format"));
    }

    @Test
    void v04_lookalike_harlowAndStern_V052_vs_V026() {
        Invoice inv = invoice("AP-T4", "V052", "HA26-2766", "2026-10-27", "5710.00", "6452.30");
        inv.setRemitAccount("593014216");
        var c = ctx(inv, harlowLookalike()).anyVendor(harlow())
                .bankAccount(account("V052", 1, "593014216", "ACTIVE", true, null))
                .bankAccount(account("V026", 2, "736063279", "ACTIVE", true, "BC-004"))
                .otherInvoice(history("AP-00100", "V026", "001400", "2026-05-01", "1000", "1130", InvoiceStatus.PAID))
                .otherInvoice(history("AP-00200", "V026", "001420", "2026-06-01", "1000", "1130", InvoiceStatus.PAID))
                .build();
        var flags = new V04LookalikeVendor().check(c);
        assertThat(flags).singleElement().satisfies(f -> {
            assertThat(f.severity().name()).isEqualTo("BLOCK");
            assertThat(f.message()).contains("\"Harlow and Stern LLP\" (V052) closely resembles \"Harlow & Stern LLP\" (V026")
                    .contains("GST # 503486760RT0001 vs 134920677RT0001").contains("bank account ...4216 vs ...3279")
                    .contains("email domain harlowandstern-llp.example vs harlow.example");
        });
        // The genuine Harlow & Stern (more history) is NOT flagged because of the lookalike.
        Invoice real = invoice("AP-T5", "V026", "001468", "2026-10-20", "1000", "1130");
        var c2 = ctx(real, harlow()).anyVendor(harlowLookalike())
                .bankAccount(account("V052", 1, "593014216", "ACTIVE", true, null)).bankAccount(account("V026", 2, "736063279", "ACTIVE", true, null))
                .otherInvoice(history("AP-00100", "V026", "001400", "2026-05-01", "1000", "1130", InvoiceStatus.PAID)).build();
        assertThat(new V04LookalikeVendor().check(c2)).isEmpty();
    }

    @Test
    void v04_duplicateRecordSharingBankAndDomain_isNotAnImpersonation() {
        // V001 / V051 Polar Freight: different GST but same bank + domain -> B04, not V04
        Invoice inv = invoice("AP-T6", "V051", "1", "2026-10-25", "100", "113");
        var c = ctx(inv, polarDup()).anyVendor(polar())
                .bankAccount(account("V001", 1, "410652201", "ACTIVE", true, null)).bankAccount(account("V051", 1, "410652201", "ACTIVE", true, null))
                .otherInvoice(history("AP-00001", "V001", "000900", "2026-05-01", "1", "1", InvoiceStatus.PAID)).build();
        assertThat(new V04LookalikeVendor().check(c)).isEmpty();
    }

    @Test
    void v05_senderDomainMismatch() {
        Invoice inv = invoice("AP-T7", "V004", "1625", "2026-10-27", "5263.09", "6051.23");
        inv.setSenderEmail("accounts@cascadia-cartage-payments.example");
        assertThat(new V05SenderDomainMismatch().check(ctx(inv, cascadia()).build()))
                .singleElement().satisfies(f -> assertThat(f.message()).contains("Sent from cascadia-cartage-payments.example"));
        inv.setSenderEmail("ar@cascadiacartage.example");
        assertThat(new V05SenderDomainMismatch().check(ctx(inv, cascadia()).build())).isEmpty();
    }

    @Test
    void b01_cascadia1625_matchesMasterButMasterIsUnverified() {
        Invoice inv = invoice("AP-T8", "V004", "1625", "2026-10-27", "5263.09", "6051.23");
        inv.setRemitAccount("953389679");
        var c = ctx(inv, cascadia()).bankAccounts(cascadiaAccounts()).bankChange(bc009()).build();
        assertThat(new B01RemitAccountDiffers().check(c)).singleElement().satisfies(f -> {
            assertThat(f.severity().name()).isEqualTo("BLOCK");
            assertThat(f.message()).contains("...9679").contains("BC-009").contains("last verified account is ...7504");
        });
    }

    @Test
    void b01_remitDiffersFromVerifiedAccount() {
        Invoice inv = invoice("AP-T9", "V001", "1", "2026-10-25", "1", "1");
        inv.setRemitAccount("999991234");
        var c = ctx(inv, polar()).bankAccount(account("V001", 1, "410652201", "ACTIVE", true, null)).build();
        assertThat(new B01RemitAccountDiffers().check(c)).singleElement()
                .satisfies(f -> assertThat(f.message()).isEqualTo("Invoice asks for payment to ...1234; the account on file is ...2201 (verified)"));
        inv.setRemitAccount("410652201");
        assertThat(new B01RemitAccountDiffers().check(c)).isEmpty();
    }

    @Test
    void b02_cascadia_bankChangedOct27ByEmailNeverVerified() {
        Invoice inv = invoice("AP-T10", "V004", "1625", "2026-10-27", "5263.09", "6051.23");
        inv.setRemitAccount("953389679");
        var c = ctx(inv, cascadia()).bankAccounts(cascadiaAccounts()).bankChange(bc009()).build();
        assertThat(new B02UnverifiedBankChange().check(c)).singleElement().satisfies(f -> {
            assertThat(f.severity().name()).isEqualTo("BLOCK");
            assertThat(f.message()).contains("switched to ...9679 on Oct 27 by email request and never verified by callback")
                    .contains("This invoice pays to that account");
        });
        // scan_8049 (inv 1624, dated Oct 24) also pays to it
        Invoice scan = invoice("AP-T11", "V004", "1624", "2026-10-24", "5821.87", "6693.69");
        scan.setRemitAccount("953389679");
        assertThat(new B02UnverifiedBankChange().check(c.toBuilder().invoice(scan).build())).hasSize(1);
    }

    @Test
    void b03_bankChangeRequestedByEmail() {
        Invoice inv = invoice("AP-T12", "V004", "1625", "2026-10-27", "1", "1");
        var c = ctx(inv, cascadia()).bankChange(bc009()).build();
        assertThat(new B03BankChangeByEmail().check(c)).singleElement().satisfies(f -> assertThat(f.message()).contains("BC-009").contains("requested by email"));
    }

    @Test
    void b04_sharedAccount_polarV001andV051() {
        Invoice inv = invoice("AP-T13", "V001", "000961", "2026-10-20", "1", "1");
        var c = ctx(inv, polar()).anyVendor(polarDup())
                .bankAccount(account("V001", 1, "410652201", "ACTIVE", true, null))
                .bankAccount(account("V051", 1, "410652201", "ACTIVE", true, null)).build();
        assertThat(new B04SharedBankAccount().check(c)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("Account ...2201 also belongs to V051 Polar Freight Lines Limited"));
    }
}
