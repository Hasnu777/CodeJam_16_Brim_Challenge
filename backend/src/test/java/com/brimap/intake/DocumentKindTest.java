package com.brimap.intake;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentKindTest {
    @Test
    void invoicesAndCreditNotesGoToReview() {
        assertThat(DocumentKind.nonInvoiceType("invoice")).isNull();
        assertThat(DocumentKind.nonInvoiceType("Invoice ")).isNull();
        assertThat(DocumentKind.nonInvoiceType("credit_note")).isNull();
        assertThat(DocumentKind.nonInvoiceType("Credit Note")).isNull();
    }

    @Test
    void unknownTypeStillGoesToReview() {
        assertThat(DocumentKind.nonInvoiceType(null)).isNull();
        assertThat(DocumentKind.nonInvoiceType("  ")).isNull();
    }

    @Test
    void statementsQuotesAndOtherAreFiled() {
        assertThat(DocumentKind.nonInvoiceType("statement")).isEqualTo("statement");
        assertThat(DocumentKind.nonInvoiceType("quote")).isEqualTo("quote");
        assertThat(DocumentKind.nonInvoiceType("other")).isEqualTo("other");
        assertThat(DocumentKind.isStatement("statement")).isTrue();
        assertThat(DocumentKind.isStatement("quote")).isFalse();
    }
}
