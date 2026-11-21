package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity @Table(name = "departments")
@Getter @Setter @NoArgsConstructor
public class Department {
    @Id private String id;
    private String name;
    private String costCentre;
    @Column(precision = 14, scale = 2) private BigDecimal annualBudgetCad;
    private String managerUserId;
}
