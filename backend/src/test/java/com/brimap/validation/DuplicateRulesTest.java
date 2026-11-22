package com.brimap.validation;

import com.brimap.model.CardTransaction;
import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.validation.rules.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.brimap.validation.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class DuplicateRulesTest {

    @Test
    void d01_exactDuplicate_cloudbarnINV01561_matchesHistoryWithoutPrefix() {
        // incoming "INV-01561" vs history AP-00001 Cloudbarn "INV-01529"... and an old copy of 01561
        Invoice inv = invoice("AP-T1", "V015", "INV-01561", "2026-10-01", "2400.00", "2712.00");
        var c = ctx(inv, cloudbarn())
                .otherInvoice(history("AP-00390", "V015", "01561", "2026-10-01", "2400.00", "2712.00", InvoiceStatus.PAID))
                .otherInvoice(history("AP-00001", "V015", "INV-01529", "2026-01-01", "2400.00", "2520.00", InvoiceStatus.PAID))
                .build();
        var flags = new D01DuplicateNumber().check(c);
        assertThat(flags).singleElement().satisfies(f -> {
            assertThat(f.severity().name()).isEqualTo("BLOCK");
            assertThat(f.message()).startsWith("Duplicate of AP-00390 (01561").contains("status PAID").contains("Score 1.0");
        });
    }

    @Test
    void d01_creditNoteIsNotADuplicateOfItsInvoice() {
        Invoice cn = invoice("AP-T2", "V043", "CN-FA-000413", "2026-10-29", "-1428.50", "-1614.21");
        var c = ctx(cn, fixwell()).otherInvoice(history("AP-00300", "V043", "FA-000413", "2026-09-01", "1428.50", "1614.21", InvoiceStatus.PAID)).build();
        assertThat(new D01DuplicateNumber().check(c)).isEmpty();
    }

    @Test
    void d02_nearDuplicate_prdExample_FA000415_vs_FA000413_3DaysApart() {
        Invoice inv = invoice("AP-T3", "V043", "FA-000415", "2026-10-28", "2536.73", "2866.50");
        var c = ctx(inv, fixwell()).otherInvoice(history("AP-00410", "V043", "FA-000413", "2026-10-25", "2530.00", "2858.90", InvoiceStatus.SCHEDULED)).build();
        assertThat(new D02PossibleDuplicate().check(c)).singleElement().satisfies(f -> {
            assertThat(f.severity().name()).isEqualTo("WARN");
            assertThat(f.message()).contains("Possible duplicate of AP-00410 (FA-000413, same vendor, $2,858.90, 3 days apart)")
                    .contains("invoice # differs by 1 character");
        });
    }

    @Test
    void d02_voyageur000903A_isAReformattedCopy_butOrion279vs281IsNot() {
        Invoice inv = invoice("AP-T8", "V046", "000903-A", "2026-10-26", "4933.07", "5574.37");
        var c = ctx(inv, null).otherInvoice(history("AP-00277", "V046", "000903", "2026-10-24", "4933.07", "5574.37", InvoiceStatus.PAID)).build();
        assertThat(new D02PossibleDuplicate().check(c)).hasSize(1);
        Invoice o279 = invoice("AP-T9", "V029", "279", "2026-10-28", "4300.00", "4859.00");
        var c2 = ctx(o279, orion()).otherInvoice(invoice("AP-T10", "V029", "281", "2026-10-28", "4320.00", "4881.60")).build();
        assertThat(new D02PossibleDuplicate().check(c2)).isEmpty();
    }

    @Test
    void d03_sameFile_and_sameVendorTotalDateDifferentNumber() {
        Invoice inv = invoice("AP-T4", "V001", "000963", "2026-10-25", "1000.00", "1130.00");
        Invoice copy = invoice("AP-T5", "V001", "000999", "2026-10-25", "1000.00", "1130.00");
        var bySha = ctx(inv, polar()).otherInvoice(copy).documentSha("abc123abc123abc").shaOf("AP-T5", "abc123abc123abc").build();
        assertThat(new D03SameDocument().check(bySha)).singleElement().satisfies(f -> assertThat(f.message()).contains("identical file"));
        var byFields = ctx(inv, polar()).otherInvoice(copy).build();
        assertThat(new D03SameDocument().check(byFields)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("same total $1,130.00 and same date 2026-10-25"));
    }

    @Test
    void d04_alreadyPaidByEmployeeCard_butNotByTheApVirtualCardPaymentOfAnotherInvoice() {
        Invoice inv = invoice("AP-T6", "V015", "INV-01600", "2026-10-15", "2400.00", "2520.00");
        CardTransaction employee = card("CT-00580", "CLOUDBARN SOFTWARE INC", "2520.00", "2026-10-20", "employee_card", null);
        CardTransaction apPayment = card("CT-00056", "CLOUDBARN SOFTWARE INC", "2520.00", "2026-10-18", "ap_virtual_card", "AP payment AP-00380");
        var c = ctx(inv, cloudbarn()).cardTransaction(employee).cardTransaction(apPayment).build();
        assertThat(new D04PaidByCard().check(c)).singleElement().satisfies(f -> {
            assertThat(f.severity().name()).isEqualTo("BLOCK");
            assertThat(f.message()).startsWith("Already paid by Brim card CT-00580 on 2026-10-20 ($2,520.00");
        });
        assertThat(D04PaidByCard.merchantMatches("PAPERCLIP STATIONERS", "Paper Trail Office Supply")).isFalse();
    }

    private static CardTransaction card(String id, String merchant, String cad, String date, String source, String memo) {
        CardTransaction t = new CardTransaction();
        t.setId(id); t.setMerchantDescriptor(merchant); t.setAmountCad(bd(cad)); t.setAmount(bd(cad)); t.setCurrency("CAD");
        t.setPostedDate(LocalDate.parse(date)); t.setSource(source); t.setMemo(memo); t.setCardholderUserId("U017");
        return t;
    }
}
