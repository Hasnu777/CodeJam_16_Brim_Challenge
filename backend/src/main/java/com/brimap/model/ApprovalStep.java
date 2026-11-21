package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** One step of an invoice's approval chain, snapshotted at submit time. */
@Entity @Table(name = "approval_steps", indexes = {@Index(columnList = "invoiceId"), @Index(columnList = "assignedUserId")})
@Getter @Setter @NoArgsConstructor
public class ApprovalStep {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Dataset id (AP-A-00001) for history; null for steps this app created. */
    private String externalId;
    private String invoiceId;
    private int seq;
    /** dept_manager, finance_manager, controller_admin, cfo. */
    private String requiredRole;
    /** Person who must act on this step (after delegation/escalation). */
    private String assignedUserId;
    /** If the step was delegated, the person the assignee acts for. */
    private String onBehalfOfUserId;
    /** Policy ids that produced this step, e.g. "P02,S01". */
    private String policyIds;
    /** Human-readable routing notes: delegation, escalation, limit bumps. */
    @Column(columnDefinition = "text") private String routingNotes;
    /** PENDING, APPROVED, REJECTED. */
    private String decision;
    private String actedBy;
    private String actedOnBehalfOf;
    private Instant actedAt;
    @Column(columnDefinition = "text") private String comment;
    private boolean historical;
}
