package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity @Table(name = "fx_rates")
@Getter @Setter @NoArgsConstructor
public class FxRate {
    @Id private LocalDate date;
    @Column(precision = 10, scale = 4) private BigDecimal usdToCad;
}
