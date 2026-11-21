package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * One structured rule. kind = approval | payment_release | match | assignment.
 * conditionJson is a small AND/OR tree of {field, op, value} evaluated by PolicyConditionEvaluator.
 * addsRoles is a comma-separated ordered list of role keys (dept_manager, finance_manager, controller_admin, cfo).
 */
@Entity @Table(name = "approval_policies")
@Getter @Setter @NoArgsConstructor
public class ApprovalPolicy {
    @Id private String id;
    private String kind;
    private int priority;
    @Column(columnDefinition = "text") private String conditionJson;
    private String addsRoles;
    private boolean enabled;
    /** The original English rule text from the dataset. */
    @Column(columnDefinition = "text") private String sourceText;
    @Column(columnDefinition = "text") private String notes;
}
