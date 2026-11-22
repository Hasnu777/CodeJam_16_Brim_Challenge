package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity @Table(name = "contracts")
@Getter @Setter @NoArgsConstructor
public class Contract {
    @Id private String id;
    private String vendorId;
    private String description;
    /** fixed_monthly, hourly, per_shipment, ... */
    private String billingType;
    @Column(precision = 14, scale = 2) private BigDecimal monthlyFee;
    private String currency;
    @Column(precision = 14, scale = 2) private BigDecimal hourlyRateCap;
    @Column(precision = 6, scale = 2) private BigDecimal fuelSurchargeCapPct;
    @Column(precision = 14, scale = 2) private BigDecimal notToExceedMonthly;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean autoRenew;
    private String status;
}
