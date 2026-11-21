package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity @Table(name = "emails")
@Getter @Setter @NoArgsConstructor
public class EmailMessage {
    @Id private String id;
    private String fromName;
    private String fromAddress;
    private String toAddress;
    private String subject;
    private LocalDateTime receivedAt;
    @Column(columnDefinition = "text") private String body;
    /** Comma-separated attachment file names. */
    @Column(columnDefinition = "text") private String attachments;
    /** Folder holding the attachments for mail fetched from the server; null for dataset emails (incoming_invoices/). */
    @Column(columnDefinition = "text") private String attachmentDir;
    /** RFC 5322 Message-ID of mail fetched from the server (dedupe); null for dataset emails. */
    @Column(columnDefinition = "text") private String messageId;
    /** INVOICE, NO_ATTACHMENT, PAYMENT_INQUIRY, STATEMENT, NOT_INVOICE; null until processed. */
    private String classification;
    /** E01..E04 for needs-attention outcomes. */
    private String intakeCode;
    /** KEYWORD or LLM: which classifier decided. */
    private String classifiedBy;
    @Column(columnDefinition = "text") private String reason;
    private boolean needsAttention;
    /** Comma-separated invoice ids created from or linked to this email. */
    private String linkedInvoiceIds;
    private Instant processedAt;
}
