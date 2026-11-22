package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity @Table(name = "card_transactions")
@Getter @Setter @NoArgsConstructor
public class CardTransaction {
    @Id private String id;
    private String cardId;
    private String cardholderUserId;
    private LocalDate postedDate;
    private String merchantDescriptor;
    private String mcc;
    @Column(precision = 14, scale = 2) private BigDecimal amount;
    private String currency;
    @Column(precision = 14, scale = 2) private BigDecimal amountCad;
    /** employee_card or ap_virtual_card. */
    private String source;
    private boolean receiptAttached;
    private String memo;
}
