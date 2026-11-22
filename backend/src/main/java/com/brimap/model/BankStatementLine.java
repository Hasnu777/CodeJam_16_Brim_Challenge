package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity @Table(name = "bank_statement_lines")
@Getter @Setter @NoArgsConstructor
public class BankStatementLine {
    @Id private String id;
    private LocalDate postedDate;
    private String description;
    @Column(precision = 14, scale = 2) private BigDecimal debit;
    @Column(precision = 14, scale = 2) private BigDecimal credit;
    private String matchedPaymentId;
    @Column(columnDefinition = "text") private String matchNote;
}
