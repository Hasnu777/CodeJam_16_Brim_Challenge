package com.brimap.auth;

import com.brimap.audit.AuditService;
import com.brimap.common.NotFoundException;
import com.brimap.model.AppUser;
import com.brimap.model.Delegation;
import com.brimap.model.UserStatus;
import com.brimap.repository.AppUserRepository;
import com.brimap.repository.DelegationRepository;
import com.brimap.config.AppClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Demo login: "log in as any user". It still issues a real server session, and every permission check
 * downstream reads the role from that session, never from the client.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AppUserRepository users;
    private final DelegationRepository delegations;
    private final SecurityContextRepository contextRepository;
    private final AuditService audit;
    private final AppClock clock;

    public record LoginRequest(@NotBlank String userId) {}

    public record UserSummary(String id, String fullName, String title, String role, String departmentId, String status) {
        static UserSummary of(AppUser u) {
            return new UserSummary(u.getId(), u.getFullName(), u.getTitle(), u.getRole().name(), u.getDepartmentId(), u.getStatus().name());
        }
    }

    /** Users for the switcher (public, demo only). */
    @GetMapping("/users")
    public List<UserSummary> switcherUsers() {
        return users.findAll().stream().sorted(Comparator.comparing(AppUser::getId)).map(UserSummary::of).toList();
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody @jakarta.validation.Valid LoginRequest body,
                                     HttpServletRequest req, HttpServletResponse res) {
        AppUser u = users.findById(body.userId()).orElseThrow(() -> new NotFoundException("User " + body.userId()));
        if (u.getStatus() == UserStatus.terminated) {
            throw new ForbiddenException(u.getFullName() + " is terminated and cannot log in");
        }
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate(); // new session id on every switch (session fixation)
        req.getSession(true);
        CurrentUser cu = CurrentUser.of(u);
        var token = new UsernamePasswordAuthenticationToken(cu, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(token);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, req, res);
        audit.recordStandalone(u.getId(), "LOGIN", "user", u.getId(), "Session started via demo user switcher");
        return me();
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("ok", true);
    }

    /** Current user, role, limit and delegations in both directions. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser cu = Auth.require();
        AppUser u = users.findById(cu.id()).orElseThrow(() -> new NotFoundException("User " + cu.id()));
        var today = clock.today();
        List<Map<String, Object>> actingFor = new ArrayList<>();
        for (Delegation d : delegations.findByToUserId(u.getId())) {
            if (!d.activeOn(today)) continue;
            AppUser from = users.findById(d.getFromUserId()).orElse(null);
            actingFor.add(Map.of("delegationId", d.getId(), "userId", d.getFromUserId(),
                    "fullName", from == null ? d.getFromUserId() : from.getFullName(), "since", d.getStartDate().toString()));
        }
        List<Map<String, Object>> delegatedTo = new ArrayList<>();
        for (Delegation d : delegations.findByFromUserId(u.getId())) {
            if (!d.activeOn(today)) continue;
            delegatedTo.add(Map.of("delegationId", d.getId(), "userId", d.getToUserId(), "since", d.getStartDate().toString()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("fullName", u.getFullName());
        m.put("title", u.getTitle());
        m.put("email", u.getEmail());
        m.put("role", u.getRole().name());
        m.put("departmentId", u.getDepartmentId());
        m.put("managerId", u.getManagerId());
        m.put("approvalLimitCad", u.getApprovalLimitCad());
        m.put("status", u.getStatus().name());
        m.put("actingFor", actingFor);
        m.put("delegatedTo", delegatedTo);
        m.put("businessDate", today.toString());
        return m;
    }
}
