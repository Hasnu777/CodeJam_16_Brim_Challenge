package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity @Table(name = "tax_codes")
@Getter @Setter @NoArgsConstructor
public class TaxCode {
    @Id private String code;
    private String description;
    @Column(precision = 8, scale = 5) private BigDecimal rate;
    /** "all" or "goods". */
    private String appliesTo;
    private String recoverable;
    private String glAccount;
}
