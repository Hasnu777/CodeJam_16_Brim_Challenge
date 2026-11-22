package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/** A simulated EFT batch file. */
@Entity @Table(name = "payment_batches")
@Getter @Setter @NoArgsConstructor
public class PaymentBatch {
    @Id private String id;
    private Instant createdAt;
    private String createdBy;
    private int paymentCount;
    @Column(precision = 14, scale = 2) private BigDecimal totalCad;
    @Column(columnDefinition = "text") private String fileContent;
}
