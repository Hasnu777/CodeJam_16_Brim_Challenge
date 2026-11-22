package com.brimap.validation;

import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.model.PurchaseOrder;
import com.brimap.validation.rules.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.brimap.validation.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class ContractPoSplitFxRulesTest {

    @Test
    void k01_torque1771_contractC025EndedJune30() {
        Invoice inv = invoice("AP-T1", "V044", "1771", "2026-10-27", "2150.00", "2257.50");
        inv.setContractId("C025");
        var c = ctx(inv, torque()).contract(contract("C025", "V044", "blanket", null, null, null, "15000.00", "2025-07-01", "2026-06-30", "expired")).build();
        assertThat(new K01ContractExpired().check(c)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("contract C025").contains("ended Jun 30, 2026"));
    }

    @Test
    void k02_fixedFee_prdExample_2400vs2640() {
        Invoice inv = invoice("AP-T2", "V015", "INV-01600", "2026-10-01", "2640.00", "2983.20");
        inv.setContractId("C001");
        var c = ctx(inv, cloudbarn()).contract(contract("C001", "V015", "fixed_monthly", "2400.00", null, null, null, "2026-01-01", "2026-12-31", "active")).build();
        assertThat(new K02FixedFeeMismatch().check(c)).singleElement()
                .satisfies(f -> assertThat(f.message()).isEqualTo("Contract C001 fee is $2,400.00/month; invoice subtotal is $2,640.00 (+10.0%)"));
    }

    @Test
    void k03_hourlyRateCap_prdExample_560vs520_harlowRetainerC022() {
        Invoice inv = invoice("AP-T3", "V026", "001470", "2026-10-28", "5600.00", "6328.00");
        inv.setContractId("C022");
        var c = ctx(inv, harlow()).contract(contract("C022", "V026", "retainer", null, "520.00", null, "20000.00", "2026-01-01", "2026-12-31", "active"))
                .line(line(1, "Legal services - hours", "10", "560.00", "5600.00", "services"))
                .line(line(2, "Disbursements and filing fees", "1", "600.00", "600.00", "services")).build();
        assertThat(new K03HourlyRateCap().check(c)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("billed $560.00/h").contains("caps the rate at $520.00/h").contains("$400.00 in total"));
    }

    @Test
    void k04_fuelSurcharge_prdExample_11_6pctVsCascadiaCap10() {
        Invoice inv = invoice("AP-T4", "V004", "1700", "2026-10-27", "4464.00", "4464.00");
        inv.setContractId("C020");
        var c = ctx(inv, cascadia()).contract(contract("C020", "V004", "rate_card", null, null, "10", "28000.00", "2026-01-01", "2026-12-31", "active"))
                .line(line(1, "LTL shipment", "1", "4000.00", "4000.00", "services"))
                .line(line(2, "Fuel surcharge (11.6%)", "1", "464.00", "464.00", "services")).build();
        assertThat(new K04FuelSurchargeCap().check(c)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("is 11.60% of $4,000.00").contains("caps it at 10%").contains("$64.00 over"));
        // Laurentian FA-002399: 11.62% against C021's 12% cap is fine
        var ok = c.toBuilder().clearContracts().contract(contract("C020", "V004", "rate_card", null, null, "12", null, "2026-01-01", "2026-12-31", "active")).build();
        assertThat(new K04FuelSurchargeCap().check(ok)).isEmpty();
    }

    @Test
    void k05_monthlyCap_prdExample_october31200vs28000() {
        Invoice inv = invoice("AP-T5", "V001", "000963", "2026-10-28", "9200.00", "10396.00");
        inv.setContractId("C018");
        var c = ctx(inv, polar()).contract(contract("C018", "V001", "rate_card", null, null, "12", "28000.00", "2026-01-01", "2026-12-31", "active"))
                .otherInvoice(history("AP-00400", "V001", "000940", "2026-10-03", "11000.00", "12430.00", InvoiceStatus.PAID))
                .otherInvoice(history("AP-00410", "V001", "000950", "2026-10-17", "11000.00", "12430.00", InvoiceStatus.SCHEDULED))
                .otherInvoice(history("AP-00300", "V001", "000900", "2026-09-17", "20000.00", "22600.00", InvoiceStatus.PAID)).build();
        assertThat(new K05MonthlyCapExceeded().check(c)).singleElement()
                .satisfies(f -> assertThat(f.message()).startsWith("October 2026 total for this vendor is $31,200.00 pre-tax across 3 invoices")
                        .contains("$28,000.00 monthly cap by $3,200.00"));
    }

    @Test
    void p13_brightlineRequiresPo() {
        Invoice inv = invoice("AP-T6", "V013", "BR26-1", "2026-10-25", "100", "105");
        assertThat(new P13PoRequired().check(ctx(inv, brightline()).build())).singleElement()
                .satisfies(f -> assertThat(f.message()).startsWith("Brightline Safety Supply (Safety Supplies) requires a PO"));
        inv.setPoId("PO-2026-0040");
        assertThat(new P13PoRequired().check(ctx(inv, brightline()).build())).isEmpty();
    }

    @Test
    void po1_closedOrWrongVendorOrMissing() {
        Invoice inv = invoice("AP-T7", "V043", "FA-1", "2026-10-25", "100", "113");
        inv.setPoId("PO-2026-0001");
        assertThat(new PO1PoProblem().check(ctx(inv, fixwell()).po(po("PO-2026-0001", "V043", "3462.86", "closed")).build()))
                .singleElement().satisfies(f -> assertThat(f.message()).startsWith("PO-2026-0001 is closed"));
        assertThat(new PO1PoProblem().check(ctx(inv, fixwell()).po(po("PO-2026-0001", "V044", "3462.86", "open")).build()))
                .singleElement().satisfies(f -> assertThat(f.message()).contains("issued to vendor V044"));
        assertThat(new PO1PoProblem().check(ctx(inv, fixwell()).build())).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("not in the PO register"));
    }

    @Test
    void p12_overPoRemaining_prdExample_1200remaining_over340() {
        Invoice inv = invoice("AP-T8", "V043", "FA-2", "2026-10-25", "1540.00", "1740.20");
        inv.setPoId("PO-2026-0050");
        var c = ctx(inv, fixwell()).po(po("PO-2026-0050", "V043", "5000.00", "open")).poConsumed(bd("3800.00")).build();
        assertThat(new P12OverPoBalance().check(c)).singleElement()
                .satisfies(f -> assertThat(f.message()).startsWith("Exceeds PO-2026-0050 remaining $1,200.00 (of $5,000.00) by $340.00"));
    }

    @Test
    void s01_orion279_281_287_allOct28_combined14543_10() {
        Invoice i279 = invoice("AP-00441", "V029", "279", "2026-10-28", "4300.00", "4859.00");
        Invoice i281 = invoice("AP-00442", "V029", "281", "2026-10-28", "4320.00", "4881.60");
        Invoice i287 = invoice("AP-00443", "V029", "287", "2026-10-28", "4250.00", "4802.50");
        for (Invoice i : new Invoice[]{i279, i281, i287}) i.setDepartmentId("D05");
        var c = ctx(i279, orion()).otherInvoice(i281).otherInvoice(i287).build();
        assertThat(new S01SplitInvoices().check(c)).singleElement().satisfies(f -> {
            assertThat(f.message()).startsWith("3 Orion Strategy Partners invoices all dated 2026-10-28")
                    .contains("total $14,543.10").contains("each is under the $10,000.00 approval limit");
            assertThat(f.evidence().get("combinedCad")).isEqualTo("14543.10");
        });
        // one invoice alone is not a split
        assertThat(new S01SplitInvoices().check(ctx(i279, orion()).build())).isEmpty();
    }

    @Test
    void fx1_and_fx2_sparkgridUsd() {
        Invoice inv = invoice("AP-T9", "V018", "FA-002342", "2026-10-26", "1950.00", "1950.00");
        inv.setCurrency("USD");
        inv.setFxRateToCad(bd("1.3471"));
        inv.setFxRateDate(LocalDate.of(2026, 10, 26));
        inv.setTotalCad(bd("2626.85"));
        assertThat(new FX1ForeignCurrency().check(ctx(inv, sparkgrid()).build())).singleElement()
                .satisfies(f -> assertThat(f.message()).isEqualTo("USD 1,950.00 = CAD 2,626.85 at the Oct 26 rate 1.3471"));
        assertThat(new FX2RateFallback().check(ctx(inv, sparkgrid()).build())).isEmpty();
        inv.setFxRateDate(LocalDate.of(2026, 10, 23));
        assertThat(new FX2RateFallback().check(ctx(inv, sparkgrid()).build())).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("used the previous available rate from 2026-10-23"));
    }

    private static PurchaseOrder po(String id, String vendorId, String total, String status) {
        PurchaseOrder p = new PurchaseOrder();
        p.setId(id); p.setVendorId(vendorId); p.setTotalAmount(bd(total)); p.setStatus(status); p.setDescription("test");
        return p;
    }
}
