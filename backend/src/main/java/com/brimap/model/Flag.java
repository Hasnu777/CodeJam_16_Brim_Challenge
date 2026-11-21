package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "flags", indexes = @Index(columnList = "invoiceId"))
@Getter @Setter @NoArgsConstructor
public class Flag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String invoiceId;
    private String ruleCode;
    @Enumerated(EnumType.STRING) private Severity severity;
    private String title;
    @Column(columnDefinition = "text") private String message;
    @Column(columnDefinition = "text") private String evidenceJson;
    /** OPEN, ACKNOWLEDGED (WARN seen by clerk), RESOLVED, OVERRIDDEN. */
    private String status;
    private String resolvedBy;
    private Instant resolvedAt;
    private String resolution;
    @Column(columnDefinition = "text") private String note;
    private Instant createdAt;

    public boolean isOpen() { return "OPEN".equals(status); }
}
