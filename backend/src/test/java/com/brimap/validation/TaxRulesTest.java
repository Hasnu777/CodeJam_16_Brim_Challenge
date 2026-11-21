package com.brimap.validation;

import com.brimap.model.Invoice;
import com.brimap.validation.rules.T01TaxVsProvince;
import com.brimap.validation.rules.T02TaxNotDue;
import com.brimap.validation.rules.T03GstWithoutRegistration;
import org.junit.jupiter.api.Test;

import static com.brimap.validation.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class TaxRulesTest {

    @Test
    void t01_beaumontFA000557_quebecLegalServicesChargedGstOnly() {
        // PRD: "Delivered to QC: expected GST $385.79 + QST $769.66; invoice charges GST only"
        Invoice inv = invoice("AP-T1", "V027", "FA-000557", "2026-10-23", "7715.88", "8101.67");
        inv.setShipToProvince("QC");
        var c = ctx(inv, beaumont()).line(line(1, "Legal services", "18", "428.66", "7715.88", "services")).tax(tax("GST5", "385.79")).build();
        assertThat(new T01TaxVsProvince().check(c)).singleElement().satisfies(f ->
                assertThat(f.message()).isEqualTo("Delivered to QC: expected GST $385.79 + QST $769.66; invoice charges GST $385.79 only"));
    }

    @Test
    void t01_correctQuebecTax_cascadia1625_isClean() {
        Invoice inv = invoice("AP-T2", "V004", "1625", "2026-10-27", "5263.09", "6051.23");
        inv.setShipToProvince("QC");
        var c = ctx(inv, cascadia()).tax(tax("GST5", "263.15")).tax(tax("QST9975", "524.99")).build();
        assertThat(new T01TaxVsProvince().check(c)).isEmpty();
    }

    @Test
    void t01_bcPstOnGoodsLinesOnly() {
        Invoice inv = invoice("AP-T3", "V011", "1", "2026-10-25", "1000.00", "1085.00");
        inv.setShipToProvince("BC");
        var c = ctx(inv, polar())
                .line(line(1, "Pallets", "10", "60.00", "600.00", "goods"))
                .line(line(2, "Delivery", "1", "400.00", "400.00", "services"))
                .tax(tax("GST5", "50.00")).tax(tax("PST7_BC", "70.00")).build(); // PST charged on services too
        assertThat(new T01TaxVsProvince().check(c)).singleElement().satisfies(f -> assertThat(f.message()).contains("PST $42.00"));
    }

    @Test
    void t01_unregisteredSmallSupplierChargingNothing_isSkipped_riverbend() {
        Invoice inv = invoice("AP-T4", "V054", "1739", "2026-10-26", "2860.00", "2860.00");
        inv.setShipToProvince("BC");
        assertThat(new T01TaxVsProvince().check(ctx(inv, riverbend()).build())).isEmpty();
    }

    @Test
    void t02_foreignVendorChargedGst_and_insuranceTaxed() {
        Invoice usd = invoice("AP-T5", "V018", "FA-002342", "2026-10-26", "1950.00", "2047.50");
        assertThat(new T02TaxNotDue().check(ctx(usd, sparkgrid()).tax(tax("GST5", "97.50")).build()))
                .singleElement().satisfies(f -> assertThat(f.message()).contains("foreign (US) vendor"));
        assertThat(new T02TaxNotDue().check(ctx(usd, sparkgrid()).build())).isEmpty(); // the real Sparkgrid invoice: no tax

        Invoice ins = invoice("AP-T6", "V034", "1", "2026-10-01", "3640.00", "4113.20");
        assertThat(new T02TaxNotDue().check(ctx(ins, guardian()).tax(tax("HST13", "473.20")).build()))
                .singleElement().satisfies(f -> assertThat(f.message()).startsWith("Insurance premiums are exempt, but $473.20"));
    }

    @Test
    void t03_gstChargedWithoutRegistration() {
        Invoice inv = invoice("AP-T7", "V054", "1739", "2026-10-26", "2860.00", "3003.00");
        assertThat(new T03GstWithoutRegistration().check(ctx(inv, riverbend()).tax(tax("GST5", "143.00")).build()))
                .singleElement().satisfies(f -> assertThat(f.message()).startsWith("$143.00 of GST/HST charged without a GST registration number"));
    }
}
