package com.brimap.intake;

import com.brimap.common.NotFoundException;
import com.brimap.config.AppProperties;
import com.brimap.model.AuditEvent;
import com.brimap.model.EmailMessage;
import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.repository.AuditEventRepository;
import com.brimap.repository.EmailMessageRepository;
import com.brimap.repository.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InboxViewControllerTest {
    @TempDir Path data;
    private final EmailMessageRepository emails = mock(EmailMessageRepository.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final AuditEventRepository audit = mock(AuditEventRepository.class);
    private InboxViewController ctl;
    private EmailMessage email;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(data.resolve("incoming_invoices"));
        Files.write(data.resolve("incoming_invoices/inv-1.pdf"), "%PDF-1.4 test".getBytes());
        Files.writeString(data.resolve("secret.txt"), "do not serve");
        ctl = new InboxViewController(emails, invoices, audit,
                new AppProperties(data.toString(), null, null, null, null, null, null, null, null, null));
        email = new EmailMessage();
        email.setId("EM-001");
        email.setSubject("Invoice 1");
        email.setAttachments("inv-1.pdf,gone.pdf");
        when(emails.findById("EM-001")).thenReturn(Optional.of(email));
        when(audit.findByEntityAndEntityIdOrderByIdAsc("email", "EM-001")).thenReturn(List.of());
    }

    @Test
    void unprocessedEmailShowsMessageAndAttachmentsOnly() {
        var v = ctl.view("EM-001");
        assertThat(v.processed()).isFalse();
        assertThat(v.attachments()).extracting(InboxViewController.AttachmentInfo::name).containsExactly("inv-1.pdf", "gone.pdf");
        assertThat(v.attachments().get(0).available()).isTrue();
        assertThat(v.attachments().get(0).contentType()).isEqualTo("application/pdf");
        assertThat(v.attachments().get(1).available()).isFalse();
        assertThat(v.linkedInvoices()).isEmpty();
    }

    @Test
    void processedEmailAddsLinkedBillsAndHistory() {
        email.setProcessedAt(Instant.now());
        email.setClassification("INVOICE");
        email.setLinkedInvoiceIds("AP-1");
        Invoice inv = new Invoice();
        inv.setId("AP-1");
        inv.setStatus(InvoiceStatus.IN_REVIEW);
        when(invoices.findById("AP-1")).thenReturn(Optional.of(inv));
        AuditEvent a = new AuditEvent();
        a.setAction("EMAIL_CLASSIFIED");
        a.setActor("U010");
        when(audit.findByEntityAndEntityIdOrderByIdAsc("email", "EM-001")).thenReturn(List.of(a));

        var v = ctl.view("EM-001");
        assertThat(v.processed()).isTrue();
        assertThat(v.linkedInvoices()).extracting(InboxViewController.LinkedInvoice::status).containsExactly("IN_REVIEW");
        assertThat(v.history()).extracting(InboxViewController.HistoryItem::action).containsExactly("EMAIL_CLASSIFIED");
    }

    @Test
    void servesListedAttachment() {
        var r = ctl.attachment("EM-001", "inv-1.pdf");
        assertThat(r.getHeaders().getContentType()).hasToString("application/pdf");
    }

    @Test
    void refusesNamesNotOnTheEmail() {
        assertThatThrownBy(() -> ctl.attachment("EM-001", "../secret.txt")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> ctl.attachment("EM-001", "other.pdf")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> ctl.attachment("EM-001", "gone.pdf")).isInstanceOf(NotFoundException.class);
    }
}
