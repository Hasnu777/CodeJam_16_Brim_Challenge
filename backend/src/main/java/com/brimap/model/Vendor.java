package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Entity @Table(name = "vendors")
@Getter @Setter @NoArgsConstructor
public class Vendor {
    @Id private String id;
    private String legalName;
    private String category;
    private String defaultGlAccount;
    private String address;
    private String provinceOrState;
    private String country;
    private String gstHstNumber;
    private String qstNumber;
    private Integer paymentTermsDays;
    /** e.g. "2/10 net 30". */
    private String earlyPayDiscount;
    private String preferredPaymentMethod;
    private boolean acceptsCardPayment;
    /** Bank details live in vendor_bank_accounts (versioned); this is only the dataset's "last updated" stamp. */
    private LocalDate bankDetailsUpdatedAt;
    private String apContactEmail;
    private boolean requiresPo;
    /** active, pending_review, on_hold. */
    private String status;
    private LocalDate onboardedAt;
    private String approvedBy;
    private String createdBy;

    public String emailDomain() {
        if (apContactEmail == null || !apContactEmail.contains("@")) return null;
        return apContactEmail.substring(apContactEmail.indexOf('@') + 1).toLowerCase();
    }
}
