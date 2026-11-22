package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "invoices", indexes = {
        @Index(columnList = "vendorId"), @Index(columnList = "status"), @Index(columnList = "normalizedInvoiceNumber")})
@Getter @Setter @NoArgsConstructor
public class Invoice {
    @Id private String id;
    private String vendorId;
    /** Vendor name exactly as read from the document (kept even when matched). */
    private String vendorNameRaw;
    /** How the vendor was matched: GST, DOMAIN, NAME, MANUAL, HISTORY, NONE. */
    private String vendorMatchMethod;
    private String vendorInvoiceNumber;
    private String normalizedInvoiceNumber;
    private LocalDate invoiceDate;
    private LocalDate receivedDate;
    private LocalDate dueDate;
    private String currency;
    @Column(precision = 14, scale = 2) private BigDecimal subtotal;
    @Column(precision = 14, scale = 2) private BigDecimal gstHstAmount;
    @Column(precision = 14, scale = 2) private BigDecimal pstQstAmount;
    @Column(precision = 14, scale = 2) private BigDecimal total;
    @Column(precision = 10, scale = 4) private BigDecimal fxRateToCad;
    private LocalDate fxRateDate;
    @Column(precision = 14, scale = 2) private BigDecimal totalCad;
    private String departmentId;
    private String requesterUserId;
    private String poId;
    private String contractId;
    private String shipToProvince;
    @Enumerated(EnumType.STRING) private InvoiceStatus status;
    /** Loaded from the 10-month history CSVs rather than processed by this app. */
    private boolean historical;
    /** HISTORY, EMAIL, UPLOAD. */
    private String source;
    private Long documentId;
    private String emailId;
    private String senderEmail;
    @Column(columnDefinition = "text") private String billToText;
    private String gstNumberOnInvoice;
    private String qstNumberOnInvoice;
    private String remitInstitution;
    private String remitTransit;
    private String remitAccount;
    private String paymentTermsText;
    private boolean creditNote;
    /** Comma-separated field names the extractor was unsure about. */
    @Column(columnDefinition = "text") private String lowConfidenceFields;
    @Column(columnDefinition = "text") private String holdReason;
    /** Status to return to when a hold is lifted. */
    @Enumerated(EnumType.STRING) private InvoiceStatus statusBeforeHold;
    /** Amount the approval chain was routed at (can exceed totalCad for split invoices). */
    @Column(precision = 14, scale = 2) private BigDecimal routedAmountCad;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private Long version;
}
