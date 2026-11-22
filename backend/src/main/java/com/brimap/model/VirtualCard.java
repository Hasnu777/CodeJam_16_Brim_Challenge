package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A Brim virtual card issued for one card payment (D-100): single use, locked to the payment's vendor, limited to the
 * payment amount, and expiring. Only the last four digits are stored; the full number goes to the vendor once.
 */
@Entity @Table(name = "virtual_cards", indexes = {@Index(columnList = "paymentId", unique = true), @Index(columnList = "vendorId")})
@Getter @Setter @NoArgsConstructor
public class VirtualCard {
    /** VC-00417 for PAY-00417: one card per payment (a retried payment gets a new payment id, so a new card). */
    @Id private String id;
    @Version @Column(columnDefinition = "bigint not null default 0") private long version;
    private String paymentId;
    private String vendorId;
    private String network;
    private String last4;
    private String currency;
    /** The most the vendor can charge: exactly the payment amount. */
    @Column(precision = 14, scale = 2) private BigDecimal spendLimit;
    private LocalDate expiresOn;
    /** ISSUED (waiting for the vendor's charge), CHARGED (used; single use), CLOSED (payment settled), CANCELLED. */
    private String status;
    private Instant issuedAt;
    private String issuedBy;
    @Column(precision = 14, scale = 2) private BigDecimal chargedAmount;
    private Instant chargedAt;
    private int declinedAttempts;
    @Column(columnDefinition = "text") private String lastDecline;

    public String masked() { return network + " ···· " + last4; }
}
