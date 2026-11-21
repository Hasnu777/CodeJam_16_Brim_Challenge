package com.brimap.extraction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicParserTest {
    /** What Tesseract (eng+fra, in the Docker image) reads from scan_8049.png: the Q of "QST" is lost. */
    static final String CASCADIA_SCAN_OCR = """
            Cascadia Cartage Ltd.
            7313 Rue Sainte-Catherine
            Surrey BC V5K 5N1
            INVOICE
            Invoice #: 1624
            Date: 2026-10-24
            GST/HST 669086667RT0001 D de bee Tiss
            Terms: Net 30
            Bill to
            Harborline Logistics Inc.
            1250 Rene-Levesque O, Bureau 2200, Montreal QC H38 4w8
            GST/HST 100000001RT0001
            Description Qty Unit price Amount
            LTL shipment Calgary-Edmonton 1 $1,682.26 $1,682.26
            LTL shipment Calgary-Edmonton 1 $1,551.94 $1,551.94
            LTL shipment Regina-Ottawa 1 $969.77 $969.77
            LTL shipment Calgary-Regina 1 $1,233.47 $1,233.47
            Fuel surcharge (7.07%) 1 $384.43 $384.43
            Subtotal $5,821.87
            GST (5%) $291.09
            ST (9.975%) $580.73
            Total CAD $6,693.69
            Remit to
            Inst. 003 Transit 92968 Acct 953389679
            """;

    @Test
    void ocrScanWithTruncatedQstLabel() {
        ExtractedInvoice x = HeuristicParser.parse(CASCADIA_SCAN_OCR);
        assertThat(x.invoiceNumber()).isEqualTo("1624");
        assertThat(x.vendorGstNumber()).isEqualTo("669086667RT0001");
        assertThat(x.lines()).hasSize(5);
        assertThat(x.taxes()).extracting(ExtractedInvoice.Tax::label).containsExactly("GST (5%)", "QST (9.975%)");
        assertThat(InvoiceNormalizer.taxCode(x.taxes().get(1).label(), x.taxes().get(1).ratePercent())).isEqualTo("QST9975");
        assertThat(x.total()).isEqualTo("$6,693.69");
        assertThat(x.remitAccount()).isEqualTo("953389679");
        assertThat(x.confidence().lines()).isEqualTo(0.8); // lines add up to the subtotal
    }

    @Test
    void garbageGivesNull() {
        assertThat(HeuristicParser.parse("hello world\nnothing here")).isNull();
        assertThat(HeuristicParser.parse("")).isNull();
    }
}
