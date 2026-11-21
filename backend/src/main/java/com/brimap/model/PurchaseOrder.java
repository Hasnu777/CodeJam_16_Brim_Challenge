package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity @Table(name = "purchase_orders")
@Getter @Setter @NoArgsConstructor
public class PurchaseOrder {
    @Id private String id;
    private String vendorId;
    private String departmentId;
    private String requesterUserId;
    private LocalDate issueDate;
    private String description;
    @Column(precision = 14, scale = 2) private BigDecimal totalAmount;
    private String currency;
    private String status;
    private String approvedBy;
}
