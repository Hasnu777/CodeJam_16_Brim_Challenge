package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity @Table(name = "invoice_lines", indexes = @Index(columnList = "invoiceId"))
@Getter @Setter @NoArgsConstructor
public class InvoiceLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String invoiceId;
    private int lineNo;
    @Column(columnDefinition = "text") private String description;
    @Column(precision = 14, scale = 4) private BigDecimal quantity;
    @Column(precision = 14, scale = 4) private BigDecimal unitPrice;
    @Column(precision = 14, scale = 2) private BigDecimal amount;
    private String glAccount;
    private String departmentId;
    private String gstHstCode;
    private String pstQstCode;
    /** goods or services. */
    private String lineType;
    private String suggestedGlAccount;
    private String suggestedDepartmentId;
    @Column(columnDefinition = "text") private String suggestionReason;
    private boolean suggestionOverridden;
    /** The clerk accepted or chose this line's coding (Accept All, or a pick in the line). Null = not yet (D-107). */
    private Boolean codingConfirmed;

    public boolean isConfirmed() { return Boolean.TRUE.equals(codingConfirmed); }
}
