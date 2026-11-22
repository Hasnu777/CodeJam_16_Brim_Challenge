package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/** One tax line as printed on the invoice, mapped to a dataset tax code. */
@Entity @Table(name = "invoice_taxes", indexes = @Index(columnList = "invoiceId"))
@Getter @Setter @NoArgsConstructor
public class InvoiceTax {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String invoiceId;
    /** Label as printed, e.g. "TPS (5 %)". */
    private String label;
    private String taxCode;
    @Column(precision = 8, scale = 5) private BigDecimal rate;
    @Column(precision = 14, scale = 2) private BigDecimal amount;
}
