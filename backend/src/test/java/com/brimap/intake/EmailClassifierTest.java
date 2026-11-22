package com.brimap.intake;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.brimap.intake.EmailClassifier.Kind.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The four no-attachment emails in dataset_given/inbox/emails.json, plus a normal invoice email. */
class EmailClassifierTest {
    @Test
    void em058InvoiceAttachedButNothingAttached() {
        var r = EmailClassifier.classify("Invoice attached", "Hello,\n\nPlease find our October invoice attached.\n\nBrightmore Consulting", List.of());
        assertThat(r.kind()).isEqualTo(NO_ATTACHMENT);
        assertThat(r.code()).isEqualTo("E01");
    }

    @Test
    void em057PaymentInquiryReferencesInvoice1743() {
        var r = EmailClassifier.classify("Where is payment for invoice 1743?",
                "Hi,\n\nWe have not seen payment for invoice 1743 dated 2026-02-04. Could you confirm the status?", List.of());
        assertThat(r.kind()).isEqualTo(PAYMENT_INQUIRY);
        assertThat(r.code()).isEqualTo("E02");
        assertThat(r.referencedInvoiceNumber()).isEqualTo("1743");
    }

    @Test
    void em056StatementOfAccount() {
        var r = EmailClassifier.classify("Statement of account - October 2026",
                "Attached is your statement of account for October. No action needed unless you see differences.", List.of());
        assertThat(r.kind()).isEqualTo(STATEMENT);
        assertThat(r.code()).isEqualTo("E03");
    }

    @Test
    void em059Newsletter() {
        var r = EmailClassifier.classify("Fall sale: 20% off ergonomic chairs",
                "Our fall sale is on. Shop now and save on ergonomic chairs and sit/stand desks.", List.of());
        assertThat(r.kind()).isEqualTo(NOT_INVOICE);
        assertThat(r.code()).isEqualTo("E04");
    }

    @Test
    void invoiceWithAttachment() {
        var r = EmailClassifier.classify("Keystone Racking Systems - Invoice KE26-1198 attached",
                "Please find attached our invoice KE26-1198.", List.of("keystonerackings-inv-002.pdf"));
        assertThat(r.kind()).isEqualTo(INVOICE);
        assertThat(r.referencedInvoiceNumber()).isEqualTo("KE26-1198");
    }

    @Test
    void marketingEmailWithBrochureIsNotAnInvoice() {
        var r = EmailClassifier.classify("Fall sale: 20% off ergonomic chairs",
                "Our fall sale is on. See the attached catalogue and shop now.", List.of("catalogue.pdf"));
        assertThat(r.kind()).isEqualTo(NOT_INVOICE);
        assertThat(r.code()).isEqualTo("E04");
    }

    @Test
    void promoWordingDoesNotOverrideAnInvoiceMention() {
        var r = EmailClassifier.classify("Invoice 4471", "Please find attached our invoice. Save up to 10% by paying early.",
                List.of("inv-4471.pdf"));
        assertThat(r.kind()).isEqualTo(INVOICE);
    }

    @Test
    void paymentDueReminderWithAttachmentIsStillAnInvoice() {
        var r = EmailClassifier.classify("Payment due: invoice INV-01561", "Reminder", List.of("cloudbarnsoftwar_INV01561.pdf"));
        assertThat(r.kind()).isEqualTo(INVOICE);
    }
}
