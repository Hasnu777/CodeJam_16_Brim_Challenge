package com.brimap.extraction;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceNormalizerTest {
    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void englishAmounts() {
        assertThat(InvoiceNormalizer.money("$1,488.34")).isEqualTo(bd("1488.34"));
        assertThat(InvoiceNormalizer.money("$6,051.23")).isEqualTo(bd("6051.23"));
        assertThat(InvoiceNormalizer.money("12.50")).isEqualTo(bd("12.50"));
        assertThat(InvoiceNormalizer.money("$1,950")).isEqualTo(bd("1950.00"));
    }

    @Test
    void frenchAmountsFromLaurentianAndPlaceMontRoyal() {
        // Laurentian FA-002399 and Place Mont-Royal FA-000566 print "1 234,56 $"
        assertThat(InvoiceNormalizer.money("4 362,67 $")).isEqualTo(bd("4362.67"));
        assertThat(InvoiceNormalizer.money("6 990,48 $")).isEqualTo(bd("6990.48"));
        assertThat(InvoiceNormalizer.money("780,00 $")).isEqualTo(bd("780.00"));
    }

    @Test
    void negativeCreditNoteAmounts() {
        // Fixwell CN-FA-000413
        assertThat(InvoiceNormalizer.money("-$1,614.21")).isEqualTo(bd("-1614.21"));
        assertThat(InvoiceNormalizer.money("($385.74)")).isEqualTo(bd("-385.74"));
        assertThat(InvoiceNormalizer.money("$-185.71")).isEqualTo(bd("-185.71"));
    }

    @Test
    void garbageIsNull() {
        assertThat(InvoiceNormalizer.money("")).isNull();
        assertThat(InvoiceNormalizer.money("n/a")).isNull();
        assertThat(InvoiceNormalizer.money(null)).isNull();
    }

    @Test
    void dates() {
        assertThat(InvoiceNormalizer.date("2026-10-27")).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(InvoiceNormalizer.date("Issued: 2026/10/27")).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(InvoiceNormalizer.date("Oct 28, 2026")).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(InvoiceNormalizer.date("28 octobre 2026")).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(InvoiceNormalizer.date("1er novembre 2026")).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(InvoiceNormalizer.date("27/10/2026")).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(InvoiceNormalizer.date("2026-13-45")).isNull();
        assertThat(InvoiceNormalizer.date("soon")).isNull();
    }

    @Test
    void taxLabelsMapToDatasetCodes() {
        assertThat(InvoiceNormalizer.taxCode("TPS (5 %)", "5")).isEqualTo("GST5");
        assertThat(InvoiceNormalizer.taxCode("TVQ (9,975 %)", "9,975")).isEqualTo("QST9975");
        assertThat(InvoiceNormalizer.taxCode("HST (13%)", "13")).isEqualTo("HST13");
        assertThat(InvoiceNormalizer.taxCode("GST (5%)", "")).isEqualTo("GST5");
        assertThat(InvoiceNormalizer.taxCode("PST (7%)", "7")).isEqualTo("PST7_BC");
        assertThat(InvoiceNormalizer.taxCode("GST/HST", "13")).isEqualTo("HST13");
        assertThat(InvoiceNormalizer.taxCode("Sales tax", "9.975")).isEqualTo("QST9975");
    }

    @Test
    void percentKeepsFrenchDecimalComma() {
        assertThat(InvoiceNormalizer.percent("9,975")).isEqualTo(bd("9.975"));
        assertThat(InvoiceNormalizer.rateFraction("9,975 %")).isEqualTo(bd("0.09975"));
    }

    @Test
    void currency() {
        assertThat(InvoiceNormalizer.currency("USD", "$1,950.00")).isEqualTo("USD");
        assertThat(InvoiceNormalizer.currency("", "US$ 120.00")).isEqualTo("USD");
        assertThat(InvoiceNormalizer.currency("CAD", "6 990,48 $")).isEqualTo("CAD");
    }
}
