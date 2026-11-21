package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Append-only, hash-chained audit row. A database trigger rejects UPDATE and DELETE on this table;
 * hash = SHA-256(prevHash | canonical fields), so any edit breaks the chain (see AuditService.verify).
 */
@Entity @Table(name = "audit_events", indexes = {@Index(columnList = "entity,entityId"), @Index(columnList = "actor")})
@Getter @Setter @NoArgsConstructor
public class AuditEvent {
    @Id private Long id;
    private Instant ts;
    private String actor;
    private String onBehalfOf;
    private String action;
    private String entity;
    private String entityId;
    @Column(columnDefinition = "text") private String beforeJson;
    @Column(columnDefinition = "text") private String afterJson;
    @Column(columnDefinition = "text") private String detail;
    private String prevHash;
    private String hash;
}
