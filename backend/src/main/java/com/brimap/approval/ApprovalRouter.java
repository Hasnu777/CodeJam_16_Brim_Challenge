package com.brimap.approval;

import com.brimap.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Builds an invoice's approval chain from the structured policy table, then resolves each step to a person.
 * Pure function of its inputs (policies, people, delegations, facts), unit-tested on the PRD's worked example.
 *
 * Step roles come from every matching 'approval' and 'match' policy, deduplicated and ordered
 * dept_manager -> finance_manager -> controller_admin -> cfo. Then, per step (the PRD's assignment rules):
 *  - Terminated assignee: skip to their manager.
 *  - On leave (P08): the active delegate acts, recorded "on behalf of".
 *  - Self-approval (P07): an assignee who is the requester escalates to their manager.
 *  - Same person twice: a person already on the chain escalates to their manager (a chain needs distinct humans).
 *  - Limit: if the final step's approver's limit is below the amount, escalate up the manager chain.
 */
public final class ApprovalRouter {
    private ApprovalRouter() {}

    public static final List<String> ROLE_ORDER = List.of("dept_manager", "finance_manager", "controller_admin", "cfo");

    public record Directory(Map<String, AppUser> users, Map<String, Department> departments, List<Delegation> delegations, LocalDate today) {
        AppUser user(String id) { return id == null ? null : users.get(id); }

        /** finance_manager = the FM; controller_admin = the ADM who has a manager (Controller); cfo = the ADM with none. */
        String holderOf(String role) {
            return switch (role) {
                case "finance_manager" -> users.values().stream().filter(u -> u.getRole() == Role.FM && u.getStatus() != UserStatus.terminated)
                        .map(AppUser::getId).sorted().findFirst().orElse(null);
                case "controller_admin" -> users.values().stream().filter(u -> u.getRole() == Role.ADM && u.getManagerId() != null)
                        .map(AppUser::getId).sorted().findFirst().orElse(null);
                case "cfo" -> users.values().stream().filter(u -> u.getRole() == Role.ADM && u.getManagerId() == null)
                        .map(AppUser::getId).sorted().findFirst().orElse(null);
                default -> null;
            };
        }

        Optional<String> activeDelegate(String userId) {
            return delegations.stream().filter(d -> d.getFromUserId().equals(userId) && d.activeOn(today)).map(Delegation::getToUserId).findFirst();
        }
    }

    public record Step(int seq, String role, String assigneeId, String onBehalfOfId, List<String> policyIds, List<String> notes) {}

    public record Result(List<Step> steps, BigDecimal amountCad, List<String> matchedPolicies, List<String> problems) {}

    public static Result route(List<ApprovalPolicy> policies, Map<String, Object> facts, String departmentId, String requesterId, Directory dir) {
        BigDecimal amount = (BigDecimal) facts.get("amountCad");
        // 1. Which roles are required, and by which policies
        Map<String, List<String>> rolePolicies = new LinkedHashMap<>();
        List<String> matched = new ArrayList<>();
        for (ApprovalPolicy p : policies.stream().sorted(Comparator.comparingInt(ApprovalPolicy::getPriority)).toList()) {
            if (!p.isEnabled() || !("approval".equals(p.getKind()) || "match".equals(p.getKind()))) continue;
            if (p.getAddsRoles() == null || p.getAddsRoles().isBlank()) continue;
            if (!PolicyConditionEvaluator.evaluate(p.getConditionJson(), facts)) continue;
            matched.add(p.getId());
            for (String role : p.getAddsRoles().split(",")) {
                rolePolicies.computeIfAbsent(role.trim(), r -> new ArrayList<>()).add(p.getId());
            }
        }
        List<String> problems = new ArrayList<>();
        if (rolePolicies.isEmpty()) {
            rolePolicies.put("dept_manager", new ArrayList<>(List.of("default")));
            problems.add("No approval policy matched; defaulting to the department manager");
        }
        List<String> roles = new ArrayList<>(rolePolicies.keySet());
        roles.sort(Comparator.comparingInt(r -> ROLE_ORDER.indexOf(r) < 0 ? 99 : ROLE_ORDER.indexOf(r)));

        // 2. Resolve each role to a person
        List<Step> steps = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < roles.size(); i++) {
            String role = roles.get(i);
            boolean finalStep = i == roles.size() - 1;
            List<String> notes = new ArrayList<>();
            String assignee = switch (role) {
                case "dept_manager" -> {
                    Department d = departmentId == null ? null : dir.departments().get(departmentId);
                    if (d == null) problems.add("No department set, so there is no department manager to route to");
                    yield d == null ? null : d.getManagerUserId();
                }
                default -> dir.holderOf(role);
            };
            if (assignee == null) {
                problems.add("Nobody holds the role " + role);
                continue;
            }
            String onBehalfOf = null;
            for (int guard = 0; guard < 12; guard++) {
                AppUser u = dir.user(assignee);
                if (u == null) { problems.add("Unknown user " + assignee); break; }
                if (u.getStatus() == UserStatus.terminated) {
                    notes.add(name(u) + " is terminated: skipped to their manager");
                    assignee = u.getManagerId(); onBehalfOf = null; continue;
                }
                if (u.getStatus() == UserStatus.on_leave && onBehalfOf == null) {
                    Optional<String> del = dir.activeDelegate(u.getId());
                    if (del.isPresent()) {
                        notes.add(name(u) + " is on leave: delegate " + name(dir.user(del.get())) + " acts on their behalf (P08)");
                        onBehalfOf = u.getId(); assignee = del.get(); continue;
                    }
                    notes.add(name(u) + " is on leave with no delegate: escalated to their manager");
                    assignee = u.getManagerId(); continue;
                }
                if (assignee.equals(requesterId)) {
                    notes.add(name(u) + " requested this invoice and cannot approve it: escalated to their manager (P07)");
                    assignee = u.getManagerId(); onBehalfOf = null; continue;
                }
                if (used.contains(assignee)) {
                    notes.add(name(u) + " already approves an earlier step: escalated to their manager (a chain needs distinct people)");
                    assignee = u.getManagerId(); onBehalfOf = null; continue;
                }
                if (finalStep && amount != null && u.getApprovalLimitCad() != null && u.getApprovalLimitCad().compareTo(amount.abs()) < 0
                        && u.getManagerId() != null) {
                    notes.add(name(u) + "'s limit " + u.getApprovalLimitCad().toPlainString() + " is below " + amount.abs().toPlainString()
                            + ": escalated to their manager");
                    assignee = u.getManagerId(); onBehalfOf = null; continue;
                }
                break;
            }
            if (assignee == null) { problems.add("Could not resolve an approver for " + role); continue; }
            used.add(assignee);
            if (onBehalfOf != null) used.add(onBehalfOf);
            steps.add(new Step(steps.size() + 1, role, assignee, onBehalfOf, rolePolicies.get(role), notes));
        }
        return new Result(steps, amount, matched, problems);
    }

    private static String name(AppUser u) { return u == null ? "?" : u.getFullName() + " (" + u.getId() + ")"; }
}
