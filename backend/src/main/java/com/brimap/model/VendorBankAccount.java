package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * One version of a vendor's bank details. A change never edits the account fields in place: it
 * creates a new version. Only status/verification columns move. Payments reference a specific version.
 */
@Entity @Table(name = "vendor_bank_accounts", indexes = @Index(columnList = "vendorId"))
@Getter @Setter @NoArgsConstructor
public class VendorBankAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String vendorId;
    private int version;
    private String institution;
    private String transit;
    /** Full account number when known; superseded historical versions only have the last 4 digits. */
    private String accountNumber;
    private String last4;
    /** ACTIVE (current), SUPERSEDED, PENDING (requested, awaiting verification), REJECTED. */
    private String status;
    private boolean verified;
    private String verifiedBy;
    private Instant createdAt;
    private String bankChangeRequestId;

    public String masked() { return "..." + last4; }
}
