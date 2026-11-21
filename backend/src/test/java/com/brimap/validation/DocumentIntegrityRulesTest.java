package com.brimap.validation;

import com.brimap.model.Invoice;
import com.brimap.validation.rules.*;
import org.junit.jupiter.api.Test;

import static com.brimap.validation.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentIntegrityRulesTest {

    @Test
    void x01_lineArithmetic_prdExample_18x428_66() {
        // PRD: "Line 2: 18 x $428.66 = $7,715.88, invoice shows $7,751.88" (Beaumont FA-000557 line, amount mistyped)
        Invoice inv = invoice("AP-T1", "V027", "FA-000557", "2026-10-23", "7751.88", "8139.47");
        var c = ctx(inv, beaumont()).line(line(2, "Legal services - Commercial contract dispute", "18", "428.66", "7751.88", "services")).build();
        var flags = new X01LineArithmetic().check(c);
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).message()).contains("18 x $428.66 = $7,715.88").contains("$7,751.88");
        // the real Beaumont line is correct
        var ok = ctx(inv, beaumont()).line(line(1, "Legal", "18", "428.66", "7715.88", "services")).build();
        assertThat(new X01LineArithmetic().check(ok)).isEmpty();
    }

    @Test
    void x02_totals_evergreen000850_isOverstatedBy100() {
        // evergreenenergyu_000850.pdf: Subtotal $3,180.00 + GST $159.00 but Total CAD $3,439.00
        Invoice inv = invoice("AP-T2", "V041", "000850", "2026-10-20", "3180.00", "3439.00");
        var c = ctx(inv, null).line(line(1, "Energy - October", "1", "3180.00", "3180.00", "services")).tax(tax("GST5", "159.00")).build();
        var flags = new X02TotalsAddUp().check(c);
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).message()).contains("$3,339.00").contains("$3,439.00").contains("$100.00");
    }

    @Test
    void x02_linesVsSubtotal_prdExample() {
        // PRD: "Lines add to $4,362.67; subtotal printed as $4,632.67"
        Invoice inv = invoice("AP-T3", "V005", "FA-002399", "2026-10-25", "4632.67", "5326.06");
        var c = ctx(inv, laurentian()).line(line(1, "LTL", "1", "4362.67", "4362.67", "services"))
                .tax(tax("GST5", "231.63")).tax(tax("QST9975", "462.11")).build();
        assertThat(new X02TotalsAddUp().check(c)).anyMatch(f -> f.message().contains("Lines add to $4,362.67; subtotal printed as $4,632.67"));
    }

    @Test
    void x03_missingInvoiceNumberAndDepartment_isFlagged() {
        Invoice inv = invoice("AP-T4", null, null, "2026-10-25", "100.00", "105.00");
        inv.setLowConfidenceFields("total");
        var flags = new X03MissingOrLowConfidence().check(ctx(inv, null).build());
        assertThat(flags).extracting(FlagDraft::message).anyMatch(m -> m.contains("Invoice number") && m.contains("Department"))
                .anyMatch(m -> m.contains("Total read with low confidence"));
    }

    @Test
    void x04_futureDate_summit000809_dated2026_11_27() {
        Invoice inv = invoice("AP-T5", "V011", "000809", "2026-11-27", "5000.00", "5748.75");
        var flags = new X04SuspiciousDates().check(ctx(inv, null).build());
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).message()).contains("25 days in the future");
    }

    @Test
    void x04_dueBeforeIssued_and_tooOld() {
        Invoice inv = invoice("AP-T6", "V001", "1", "2026-03-01", "1.00", "1.00");
        inv.setDueDate(java.time.LocalDate.of(2026, 2, 1));
        assertThat(new X04SuspiciousDates().check(ctx(inv, null).build())).hasSize(2);
    }

    @Test
    void x05_creditNote_fixwellCnFa000413() {
        Invoice inv = invoice("AP-T7", "V043", "CN-FA-000413", "2026-10-29", "-1428.50", "-1614.21");
        inv.setCreditNote(true);
        var c = ctx(inv, fixwell()).otherInvoice(history("AP-00383", "V043", "FA-000410", "2026-09-21", "1428.50", "1614.21",
                com.brimap.model.InvoiceStatus.PAID)).build();
        var flags = new X05CreditNote().check(c);
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).message()).contains("Credit note for $1,614.21").contains("Fixwell")
                .contains("It matches FA-000410 (AP-00383, PAID) for the same amount");
    }
}
