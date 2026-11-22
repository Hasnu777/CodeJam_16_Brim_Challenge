package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * How a reconciliation leftover was explained (D-109): one row per item, the latest decision. Every decision is also an
 * audit event, so the history of an item (investigating, then disputed) stays in the audit log.
 */
@Entity @Table(name = "recon_resolutions")
@Getter @Setter @NoArgsConstructor
public class ReconResolution {
    /** "LINE:BK-00064" (statement line) or "PAYMENT:PAY-00347" (payment not on the statement). */
    @Id private String itemKey;
    private String kind;
    private String itemId;
    private String resolution;
    @Column(columnDefinition = "text") private String note;
    private String resolvedBy;
    private Instant resolvedAt;
}
