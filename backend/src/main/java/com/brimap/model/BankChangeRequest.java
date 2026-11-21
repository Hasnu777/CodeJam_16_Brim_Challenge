package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "bank_change_requests")
@Getter @Setter @NoArgsConstructor
public class BankChangeRequest {
    @Id private String id;
    private String vendorId;
    private LocalDate changedAt;
    private String requestedBy;
    /** phone_callback_verified, vendor_portal, email_request, ... */
    private String requestChannel;
    private boolean verifiedByCallback;
    private String oldAccountLast4;
    private String newAccountLast4;
    private String newInstitution;
    private String newTransit;
    private String newAccountNumber;
    /** PENDING (not verified; payments held), VERIFIED, REJECTED. */
    private String status;
    private String verifiedBy;
    private Instant verifiedAt;
    @Column(columnDefinition = "text") private String note;
    /** True when the dataset already applied the change to the vendor master before verification (BC-009). */
    private boolean appliedBeforeVerification;
}
