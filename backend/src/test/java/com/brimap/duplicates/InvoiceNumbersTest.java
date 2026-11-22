package com.brimap.duplicates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceNumbersTest {
    @Test
    void stripsPrefixesPunctuationAndLeadingZeros() {
        assertThat(InvoiceNumbers.normalize("INV-01561")).isEqualTo("1561");
        assertThat(InvoiceNumbers.normalize("FA-000557")).isEqualTo("557");
        assertThat(InvoiceNumbers.normalize("000961")).isEqualTo("961");
        assertThat(InvoiceNumbers.normalize(" inv 01561 ")).isEqualTo("1561");
    }

    @Test
    void keepsCreditNotePrefixSoCreditNotesAreNotDuplicatesOfInvoices() {
        assertThat(InvoiceNumbers.normalize("CN-FA-000413")).isEqualTo("CN413");
        assertThat(InvoiceNumbers.normalize("FA-000413")).isEqualTo("413");
    }

    @Test
    void keepsVendorSpecificAlphaPrefixes() {
        assertThat(InvoiceNumbers.normalize("HA26-2766")).isEqualTo("HA262766");
        assertThat(InvoiceNumbers.normalize("AU26-2207")).isEqualTo("AU262207");
    }

    @Test
    void distance() {
        assertThat(InvoiceNumbers.distance("1625", "1624")).isEqualTo(1);
        assertThat(InvoiceNumbers.distance("415", "413")).isEqualTo(1);
    }
}
