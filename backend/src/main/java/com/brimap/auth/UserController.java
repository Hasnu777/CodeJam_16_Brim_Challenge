package com.brimap.auth;

import com.brimap.audit.AuditService;
import com.brimap.common.BusinessRuleException;
import com.brimap.common.NotFoundException;
import com.brimap.config.AppClock;
import com.brimap.model.AppUser;
import com.brimap.model.Delegation;
import com.brimap.model.Role;
import com.brimap.model.UserStatus;
import com.brimap.repository.AppUserRepository;
import com.brimap.repository.DelegationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {
    private final AppUserRepository users;
    private final DelegationRepository delegations;
    private final AuditService audit;
    private final AppClock clock;

    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('ADM','AUD')")
    public List<AppUser> list() {
        return users.findAll().stream().sorted(Comparator.comparing(AppUser::getId)).toList();
    }

    public record UserUpdate(String role, BigDecimal approvalLimitCad, String status, String delegateUserId, String managerId) {}

    @PutMapping("/users/{id}")
    @PreAuthorize("hasRole('ADM')")
    @Transactional
    public AppUser update(@PathVariable String id, @RequestBody UserUpdate body) {
        AppUser u = users.findById(id).orElseThrow(() -> new NotFoundException("User " + id));
        Map<String, Object> before = snapshot(u);
        if (body.role() != null) u.setRole(Role.valueOf(body.role()));
        if (body.approvalLimitCad() != null) {
            if (body.approvalLimitCad().signum() < 0) throw new IllegalArgumentException("Limit cannot be negative");
            u.setApprovalLimitCad(body.approvalLimitCad());
        }
        if (body.managerId() != null) {
            if (body.managerId().equals(id)) throw new IllegalArgumentException("A user cannot be their own manager");
            users.findById(body.managerId()).orElseThrow(() -> new NotFoundException("Manager " + body.managerId()));
            u.setManagerId(body.managerId());
        }
        if (body.delegateUserId() != null) u.setDelegateUserId(body.delegateUserId().isBlank() ? null : body.delegateUserId());
        if (body.status() != null) {
            UserStatus st = UserStatus.valueOf(body.status());
            if (st != u.getStatus()) {
                u.setStatus(st);
                u.setStatusSince(clock.today());
                if (st == UserStatus.on_leave && u.getDelegateUserId() != null) {
                    createDelegation(u.getId(), u.getDelegateUserId(), clock.today(), null, "On leave (set by admin)");
                }
                if (st == UserStatus.active) endDelegations(u.getId());
            }
        }
        audit.record("USER_UPDATED", "user", id, before, snapshot(u), null);
        return u;
    }

    public record DelegationRequest(@NotBlank String fromUser, @NotBlank String toUser, @NotNull LocalDate start, LocalDate end, String reason) {}

    /** Set a delegate while away. APR/FM/ADM may only delegate their own approvals. */
    @PostMapping("/delegations")
    @PreAuthorize("hasAnyRole('APR','FM','ADM')")
    @Transactional
    public Delegation delegate(@RequestBody @Valid DelegationRequest body) {
        CurrentUser me = Auth.require();
        if (!me.id().equals(body.fromUser())) {
            throw new ForbiddenException("You can only delegate your own approvals (you are " + me.id() + ", request is for " + body.fromUser() + ")");
        }
        if (body.fromUser().equals(body.toUser())) throw new IllegalArgumentException("Cannot delegate to yourself");
        AppUser to = users.findById(body.toUser()).orElseThrow(() -> new NotFoundException("User " + body.toUser()));
        if (to.getStatus() != UserStatus.active) throw new BusinessRuleException(to.getFullName() + " is " + to.getStatus() + " and cannot be a delegate");
        if (to.getRole() == Role.EMP || to.getRole() == Role.CLK || to.getRole() == Role.AUD) {
            throw new BusinessRuleException(to.getFullName() + " (" + to.getRole() + ") cannot approve invoices, so cannot be a delegate");
        }
        if (body.end() != null && body.end().isBefore(body.start())) throw new IllegalArgumentException("End is before start");
        return createDelegation(body.fromUser(), body.toUser(), body.start(), body.end(), body.reason());
    }

    @GetMapping("/delegations")
    @PreAuthorize("isAuthenticated()")
    public List<Delegation> delegationsList() { return delegations.findAll(); }

    private Delegation createDelegation(String from, String to, LocalDate start, LocalDate end, String reason) {
        Delegation d = new Delegation();
        d.setFromUserId(from);
        d.setToUserId(to);
        d.setStartDate(start);
        d.setEndDate(end);
        d.setReason(reason);
        d = delegations.save(d);
        audit.record("DELEGATION_CREATED", "delegation", String.valueOf(d.getId()), null,
                Map.of("from", from, "to", to, "start", start.toString(), "end", String.valueOf(end)), reason);
        return d;
    }

    private void endDelegations(String from) {
        LocalDate y = clock.today().minusDays(1);
        for (Delegation d : delegations.findByFromUserId(from)) {
            if (d.activeOn(clock.today())) {
                d.setEndDate(y.isBefore(d.getStartDate()) ? d.getStartDate() : y);
                audit.record("DELEGATION_ENDED", "delegation", String.valueOf(d.getId()), null, Map.of("end", d.getEndDate().toString()), null);
            }
        }
    }

    private static Map<String, Object> snapshot(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", u.getRole());
        m.put("approvalLimitCad", u.getApprovalLimitCad());
        m.put("status", u.getStatus());
        m.put("managerId", u.getManagerId());
        m.put("delegateUserId", u.getDelegateUserId());
        return m;
    }
}
