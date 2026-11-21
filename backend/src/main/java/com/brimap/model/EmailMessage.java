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
