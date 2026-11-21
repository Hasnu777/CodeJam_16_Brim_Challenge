package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "payments", indexes = {@Index(columnList = "invoiceId"), @Index(columnList = "status")})
@Getter @Setter @NoArgsConstructor
public class Payment {
    @Id private String id;
    private String invoiceId;
    private String vendorId;
    /** EFT, CHEQUE, CARD. */
    private String method;
    private LocalDate scheduledDate;
    private LocalDate paidDate;
    private String currency;
    @Column(precision = 14, scale = 2) private BigDecimal amount;
    @Column(precision = 10, scale = 4) private BigDecimal fxRateToCad;
    @Column(precision = 14, scale = 2) private BigDecimal amountCad;
    @Column(precision = 14, scale = 2) private BigDecimal discountTaken;
    /** Last day the early-payment discount can be taken, if the vendor offers one. */
    private LocalDate discountDeadline;
    private String reference;
    /** SCHEDULED, RELEASED, SENT, SETTLED, FAILED. */
    private String status;
    private String preparedBy;
    private String releasedBy;
    private Instant releasedAt;
    private String batchId;
    /** The exact bank account version this payment goes to. */
    private Long bankAccountId;
    @Column(columnDefinition = "text") private String failureReason;
    /** Credit notes netted against this payment (comma-separated invoice ids) and the total credited. */
    private String appliedCreditInvoiceIds;
    @Column(precision = 14, scale = 2) private BigDecimal creditApplied;
    private boolean historical;
}
