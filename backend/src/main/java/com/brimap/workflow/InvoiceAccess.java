package com.brimap.workflow;

import com.brimap.auth.CurrentUser;
import com.brimap.auth.ForbiddenException;
import com.brimap.config.AppClock;
import com.brimap.model.ApprovalStep;
import com.brimap.model.Invoice;
import com.brimap.repository.ApprovalStepRepository;
import com.brimap.repository.DelegationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Row-level scope (PRD "Scope"): Employee sees invoices they requested or uploaded; Approver sees their own
 * department plus anything assigned to them or to someone they are delegate for; other roles see all.
 */
@Component
@RequiredArgsConstructor
public class InvoiceAccess {
    private final ApprovalStepRepository steps;
    private final DelegationRepository delegations;
    private final AppClock clock;

    public boolean canView(CurrentUser u, Invoice i) {
        return switch (u.role()) {
            case EMP -> u.id().equals(i.getRequesterUserId());
            case APR -> u.departmentId() != null && u.departmentId().equals(i.getDepartmentId()) || isAssignedTo(u, i);
            case CLK, FM, ADM, AUD -> true;
        };
    }

    public void requireView(CurrentUser u, Invoice i) {
        if (!canView(u, i)) {
            throw new ForbiddenException(switch (u.role()) {
                case EMP -> "Employees can only see invoices they submitted; " + i.getId() + " belongs to someone else";
                case APR -> "Approvers can only see their own department's invoices; " + i.getId() + " is in " + i.getDepartmentId();
                default -> "Not allowed to view " + i.getId();
            });
        }
    }

    /** User ids this person can act for today (themselves + active delegators). */
    public Set<String> actingIds(String userId) {
        Set<String> ids = delegations.findByToUserId(userId).stream()
                .filter(d -> d.activeOn(clock.today())).map(d -> d.getFromUserId()).collect(Collectors.toSet());
        ids.add(userId);
        return ids;
    }

    private boolean isAssignedTo(CurrentUser u, Invoice i) {
        Set<String> ids = actingIds(u.id());
        for (ApprovalStep s : steps.findByInvoiceIdOrderBySeq(i.getId())) {
            if (ids.contains(s.getAssignedUserId()) || u.id().equals(s.getActedBy())) return true;
        }
        return false;
    }
}
