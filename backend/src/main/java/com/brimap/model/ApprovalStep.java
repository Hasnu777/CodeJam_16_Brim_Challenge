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
    /**
     * True for steps from an earlier approval round (the invoice was rejected, reopened and resubmitted). They stay for
     * history and still count for "nobody pays what they approved" (P11), but no longer drive the current chain.
     */
    @Column(columnDefinition = "boolean not null default false") private boolean superseded;
    /** Optimistic lock: two people acting on the same step at once -> the second gets 409, not a double decision. */
    @Version @Column(columnDefinition = "bigint not null default 0") private long version;

    /** Part of the invoice's current approval round (not an earlier round, not cancelled by a resubmit or re-route). */
    public boolean isCurrent() { return !historical && !superseded && !"CANCELLED".equals(decision); }
}
