package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity @Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id private String id;
    private String fullName;
    private String email;
    private String title;
    private String departmentId;
    private String managerId;
    @Enumerated(EnumType.STRING) private Role role;
    @Column(precision = 14, scale = 2) private BigDecimal approvalLimitCad;
    private String workProvince;
    @Enumerated(EnumType.STRING) private UserStatus status;
    private LocalDate statusSince;
    /** Standing delegate from the HR record; time-bounded delegations live in {@link Delegation}. */
    private String delegateUserId;
    private LocalDate hireDate;
}
