package com.brimap.approval;

import com.brimap.audit.AuditService;
import com.brimap.auth.Auth;
import com.brimap.auth.CurrentUser;
import com.brimap.auth.ForbiddenException;
import com.brimap.common.BusinessRuleException;
import com.brimap.common.Json;
import com.brimap.config.AppClock;
import com.brimap.model.*;
import com.brimap.repository.*;
import com.brimap.validation.ValidationService;
import com.brimap.workflow.InvoiceAccess;
import com.brimap.workflow.InvoiceWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Submit -> route -> approve/reject. Routing reads only the approval_policies table; the resolved chain is
 * snapshotted into approval_steps at submit time so later policy edits don't rewrite history.
 */
@Service
@RequiredArgsConstructor
public class ApprovalService {
    /**
     * BLOCK flags that hold PAYMENT, not approval (PRD "Holds"): an unknown/unapproved vendor or an unverified bank
     * change can still be routed (P05 adds the FM step) but no money moves until the FM approves the vendor or
     * verifies the change. Every other BLOCK flag stops submission and approval.
     */
    public static final Set<String> PAYMENT_HOLD_CODES = Set.of("V01", "V02", "B01", "B02");

    private final InvoiceWorkflowService workflow;
    private final ApprovalPolicyRepository policies;
    private final ApprovalStepRepository steps;
    private final AppUserRepository users;
    private final DepartmentRepository departments;
    private final DelegationRepository delegations;
    private final VendorRepository vendors;
    private final PurchaseOrderRepository pos;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final FlagRepository flags;
    private final ValidationService validation;
    private final InvoiceAccess access;
    private final AuditService audit;
    private final AppClock clock;

    // ---------- routing ----------

    public ApprovalRouter.Directory directory() {
        return new ApprovalRouter.Directory(
                users.findAll().stream().collect(Collectors.toMap(AppUser::getId, u -> u)),
                departments.findAll().stream().collect(Collectors.toMap(Department::getId, d -> d)),
                delegations.findAll(), clock.today());
    }

    /** Facts the policy conditions can reference. */
    public Map<String, Object> facts(Invoice inv, List<Flag> openFlags) {
        Map<String, Object> f = new HashMap<>();
        BigDecimal amount = inv.getTotalCad() == null ? BigDecimal.ZERO : inv.getTotalCad().abs();
        BigDecimal splitCombined = openFlags.stream().filter(x -> "S01".equals(x.getRuleCode()))
                .map(x -> com.brimap.validation.rules.S01SplitInvoices.combinedFromEvidence(evidence(x))).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        f.put("amountCad", splitCombined != null && splitCombined.compareTo(amount) > 0 ? splitCombined : amount);
        f.put("invoiceAmountCad", amount);
        f.put("department", inv.getDepartmentId());
        f.put("currency", inv.getCurrency());
        Vendor v = inv.getVendorId() == null ? null : vendors.findById(inv.getVendorId()).orElse(null);
        f.put("vendor.unknown", v == null);
        if (v != null) {
            f.put("vendor.status", v.getStatus());
            f.put("vendor.category", v.getCategory());
            f.put("vendor.requiresPo", v.isRequiresPo());
            if (v.getOnboardedAt() != null) f.put("vendor.daysSinceOnboarded", ChronoUnit.DAYS.between(v.getOnboardedAt(), clock.today()));
        }
        f.put("po.present", inv.getPoId() != null);
        f.put("po.overBalance", openFlags.stream().anyMatch(x -> "P12".equals(x.getRuleCode())));
        for (Flag x : openFlags) f.put("flags." + x.getRuleCode(), true);
        return f;
    }

    public ApprovalRouter.Result preview(Invoice inv, List<ApprovalPolicy> policyRows) {
        List<Flag> open = flags.findByInvoiceId(inv.getId()).stream().filter(fl -> !"RESOLVED".equals(fl.getStatus())).toList();
        return ApprovalRouter.route(policyRows, facts(inv, open), inv.getDepartmentId(), inv.getRequesterUserId(), directory());
    }

    // ---------- submit ----------

    /** Clerk done: re-validate, check nothing blocks, route, snapshot the chain, PENDING_APPROVAL. */
    @Transactional
    public Invoice submit(String id) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "submit invoices for approval", Role.CLK);
        Invoice inv = workflow.load(id);
        if (inv.getStatus() != InvoiceStatus.IN_REVIEW) {
            throw new BusinessRuleException(id + " is " + inv.getStatus() + "; only invoices in review can be submitted");
        }
        List<Flag> open = validation.validate(inv);
        List<String> problems = new ArrayList<>();
        List<Flag> blocking = open.stream().filter(f -> f.getSeverity() == Severity.BLOCK && !PAYMENT_HOLD_CODES.contains(f.getRuleCode())).toList();
        if (!blocking.isEmpty()) {
            problems.add("Unresolved BLOCK flags: " + blocking.stream().map(f -> f.getRuleCode() + " " + f.getTitle()).collect(Collectors.joining(", "))
                    + " (a Finance Manager must resolve or override them)");
        }
        List<Flag> unacked = open.stream().filter(f -> f.getSeverity() == Severity.WARN).toList();
        if (!unacked.isEmpty()) {
            problems.add("Acknowledge the warnings first: " + unacked.stream().map(Flag::getRuleCode).distinct().collect(Collectors.joining(", ")));
        }
        if (inv.getDepartmentId() == null) problems.add("Set the department");
        if (inv.getTotal() == null || inv.getInvoiceDate() == null || inv.getVendorInvoiceNumber() == null) {
            problems.add("Invoice number, date and total are required");
        }
        List<InvoiceLine> ls = lines.findByInvoiceIdOrderByLineNo(id);
        if (ls.stream().anyMatch(l -> l.getGlAccount() == null)) problems.add("Every line needs a GL account");
        if (!problems.isEmpty()) throw new BusinessRuleException("Cannot submit " + id + ": " + String.join("; ", problems));

        Invoice fresh = workflow.load(id);
        List<Flag> stillOpen = flags.findByInvoiceId(id).stream().filter(f -> !"RESOLVED".equals(f.getStatus()) && !"AUTO_CLEARED".equals(f.getResolution())).toList();
        ApprovalRouter.Result r = ApprovalRouter.route(policies.findAllByOrderByPriorityAsc(), facts(fresh, stillOpen),
                fresh.getDepartmentId(), fresh.getRequesterUserId(), directory());
        if (r.steps().isEmpty() || !r.problems().stream().filter(p -> !p.startsWith("No approval policy matched")).toList().isEmpty()) {
            throw new BusinessRuleException("Cannot route " + id + ": " + String.join("; ", r.problems()));
        }
        // Snapshot the chain. An earlier round (rejected, reopened, resubmitted) is kept for history but superseded, so
        // its approvers can act again in the new round and it no longer counts for "final step" or the queue.
        List<ApprovalStep> earlier = steps.findByInvoiceIdAndHistoricalFalseOrderBySeq(id);
        int seqBase = earlier.stream().mapToInt(ApprovalStep::getSeq).max().orElse(0);
        for (ApprovalStep s : earlier) {
            if (s.isSuperseded()) continue;
            if ("PENDING".equals(s.getDecision())) s.setDecision("CANCELLED");
            s.setSuperseded(true);
            steps.save(s);
        }
        for (ApprovalRouter.Step s : r.steps()) {
            ApprovalStep row = new ApprovalStep();
            row.setInvoiceId(id);
            row.setSeq(seqBase + s.seq());
            row.setRequiredRole(s.role());
            row.setAssignedUserId(s.assigneeId());
            row.setOnBehalfOfUserId(s.onBehalfOfId());
            row.setPolicyIds(String.join(",", s.policyIds()));
            row.setRoutingNotes(s.notes().isEmpty() ? null : String.join("\n", s.notes()));
            row.setDecision("PENDING");
            steps.save(row);
        }
        fresh.setRoutedAmountCad((BigDecimal) r.amountCad());
        String chain = r.steps().stream().map(s -> s.seq() + ". " + s.role() + " -> " + s.assigneeId()
                + (s.onBehalfOfId() == null ? "" : " on behalf of " + s.onBehalfOfId())).collect(Collectors.joining("; "));
        String why = "Routed at CAD " + r.amountCad().toPlainString() + " (policies " + String.join(", ", r.matchedPolicies()) + "): " + chain;
        return workflow.transition(fresh, InvoiceStatus.PENDING_APPROVAL, "SUBMITTED_FOR_APPROVAL", why);
    }

    // ---------- approve / reject ----------

    public record Decision(String comment, String reason) {}

    @Transactional
    public Invoice approve(String id, String comment) { return decide(id, true, comment); }

    @Transactional
    public Invoice reject(String id, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A rejection needs a reason");
        return decide(id, false, reason);
    }

    private Invoice decide(String id, boolean approve, String comment) {
        CurrentUser u = Auth.require();
        Invoice inv = workflow.load(id);
        if (inv.getStatus() != InvoiceStatus.PENDING_APPROVAL) throw new BusinessRuleException(id + " is " + inv.getStatus() + ", not waiting for approval");
        List<ApprovalStep> chain = currentRound(id);
        ApprovalStep current = chain.stream().filter(s -> "PENDING".equals(s.getDecision())).findFirst()
                .orElseThrow(() -> new BusinessRuleException(id + " has no pending approval step"));

        // Who may act: the assignee, or someone the assignee has an active delegation to.
        Set<String> canActAs = access.actingIds(u.id());
        String actingFor;
        if (u.id().equals(current.getAssignedUserId())) actingFor = current.getOnBehalfOfUserId();
        else if (canActAs.contains(current.getAssignedUserId())) actingFor = current.getAssignedUserId();
        else throw new ForbiddenException("Step " + current.getSeq() + " (" + current.getRequiredRole() + ") is assigned to "
                    + current.getAssignedUserId() + ", not you");
        if (u.id().equals(inv.getRequesterUserId())) {
            throw new ForbiddenException("You requested " + id + " and cannot approve it (self-approval, P07)");
        }
        if (actingFor != null && actingFor.equals(inv.getRequesterUserId())) {
            throw new ForbiddenException("You would be acting on behalf of " + actingFor + ", who requested " + id
                    + "; that is self-approval by proxy (P07). An Admin can re-route the step.");
        }
        boolean actedBefore = chain.stream().anyMatch(s -> s != current && (u.id().equals(s.getActedBy()) || u.id().equals(s.getActedOnBehalfOf())));
        if (actedBefore) throw new ForbiddenException("You already acted on an earlier step of " + id + "; a chain needs distinct people");
        AppUser me = users.findById(u.id()).orElseThrow();
        boolean finalStep = chain.indexOf(current) == chain.size() - 1;
        BigDecimal amount = inv.getRoutedAmountCad() != null ? inv.getRoutedAmountCad() : inv.getTotalCad().abs();
        if (approve && finalStep && me.getApprovalLimitCad() != null && me.getApprovalLimitCad().compareTo(amount) < 0) {
            throw new ForbiddenException("Your approval limit (" + me.getApprovalLimitCad().toPlainString() + ") is below CAD " + amount.toPlainString());
        }
        if (approve) {
            List<Flag> blocking = flags.findByInvoiceIdAndStatus(id, "OPEN").stream()
                    .filter(f -> f.getSeverity() == Severity.BLOCK && !PAYMENT_HOLD_CODES.contains(f.getRuleCode())).toList();
            if (!blocking.isEmpty()) {
                throw new BusinessRuleException("Cannot approve while BLOCK flags are open: "
                        + blocking.stream().map(Flag::getRuleCode).collect(Collectors.joining(", ")));
            }
        }
        current.setDecision(approve ? "APPROVED" : "REJECTED");
        current.setActedBy(u.id());
        current.setActedOnBehalfOf(actingFor);
        current.setActedAt(clock.now());
        current.setComment(comment);
        steps.save(current);
        String who = me.getFullName() + (actingFor == null ? "" : " on behalf of " + users.findById(actingFor).map(AppUser::getFullName).orElse(actingFor));
        String detail = "Step " + current.getSeq() + " (" + current.getRequiredRole() + ") " + (approve ? "approved" : "rejected") + " by " + who
                + (comment == null || comment.isBlank() ? "" : ": " + comment);
        if (!approve) {
            chain.stream().filter(s -> "PENDING".equals(s.getDecision())).forEach(s -> { s.setDecision("CANCELLED"); steps.save(s); });
            return workflow.transition(inv, InvoiceStatus.REJECTED, "REJECTED", detail, actingFor);
        }
        boolean last = chain.stream().noneMatch(s -> "PENDING".equals(s.getDecision()));
        if (last) return workflow.transition(inv, InvoiceStatus.APPROVED, "APPROVED", detail + "; all steps complete", actingFor);
        return workflow.recordChangeOnBehalf(inv, "APPROVAL_STEP_APPROVED", actingFor, Map.of("step", current.getSeq()), detail);
    }

    /** The invoice's current approval round, in order: no history, no superseded round, no cancelled steps. */
    List<ApprovalStep> currentRound(String invoiceId) {
        return steps.findByInvoiceIdAndHistoricalFalseOrderBySeq(invoiceId).stream().filter(ApprovalStep::isCurrent).toList();
    }

    // ---------- reopen (gap 1) ----------

    /**
     * A rejected bill goes back to the AP clerk to fix and resubmit (REJECTED -> IN_REVIEW). The old round's steps stay
     * for history; resubmitting starts a fresh round. Clerk or Admin, with a reason.
     */
    @Transactional
    public Invoice reopen(String id, String reason) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "reopen rejected invoices", Role.CLK, Role.ADM);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Say why the invoice is being reopened");
        Invoice inv = workflow.load(id);
        if (inv.getStatus() != InvoiceStatus.REJECTED) {
            throw new BusinessRuleException(id + " is " + inv.getStatus() + "; only rejected invoices can be reopened");
        }
        if (inv.isHistorical()) throw new BusinessRuleException(id + " is a historical invoice and is read-only");
        return workflow.transition(inv, InvoiceStatus.IN_REVIEW, "REOPENED", reason);
    }

    // ---------- re-route (gap 3) ----------

    /**
     * Admin fix for a stuck chain: an approver left, went on leave without a delegate, or lost the role after the chain
     * was snapshotted. Re-resolves only the steps still PENDING against today's people and delegations; steps already
     * decided stay, and everyone who acted (or was acted for) counts as on the chain, so nobody approves twice.
     */
    @Transactional
    public Invoice reroute(String id, String reason) {
        CurrentUser u = Auth.require();
        InvoiceWorkflowService.requireRole(u, "re-route approval chains", Role.ADM);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Say why the chain is being re-routed");
        Invoice inv = workflow.load(id);
        if (inv.getStatus() != InvoiceStatus.PENDING_APPROVAL) throw new BusinessRuleException(id + " is " + inv.getStatus() + ", not waiting for approval");
        List<ApprovalStep> round = currentRound(id);
        List<ApprovalStep> pending = round.stream().filter(s -> "PENDING".equals(s.getDecision())).toList();
        if (pending.isEmpty()) throw new BusinessRuleException(id + " has no pending approval step");
        Set<String> acted = new HashSet<>();
        round.stream().filter(s -> !"PENDING".equals(s.getDecision())).forEach(s -> {
            if (s.getActedBy() != null) acted.add(s.getActedBy());
            if (s.getActedOnBehalfOf() != null) acted.add(s.getActedOnBehalfOf());
        });
        Set<String> roles = pending.stream().map(ApprovalStep::getRequiredRole).collect(Collectors.toSet());
        List<Flag> stillOpen = flags.findByInvoiceId(id).stream().filter(f -> !"RESOLVED".equals(f.getStatus()) && !"AUTO_CLEARED".equals(f.getResolution())).toList();
        Map<String, Object> facts = facts(inv, stillOpen);
        if (inv.getRoutedAmountCad() != null) facts.put("amountCad", inv.getRoutedAmountCad()); // same amount the chain was built for
        ApprovalRouter.Result r = ApprovalRouter.route(policies.findAllByOrderByPriorityAsc(), facts, inv.getDepartmentId(),
                inv.getRequesterUserId(), directory(), acted, roles);
        if (r.steps().size() != pending.size() || !r.problems().stream().filter(p -> !p.startsWith("No approval policy matched")).toList().isEmpty()) {
            throw new BusinessRuleException("Cannot re-route " + id + ": " + (r.problems().isEmpty() ? "the policy roles changed; reject and resubmit instead" : String.join("; ", r.problems())));
        }
        boolean same = true;
        for (int i = 0; i < pending.size(); i++) {
            ApprovalRouter.Step n = r.steps().get(i);
            ApprovalStep o = pending.get(i);
            same &= Objects.equals(n.assigneeId(), o.getAssignedUserId()) && Objects.equals(n.onBehalfOfId(), o.getOnBehalfOfUserId());
        }
        if (same) throw new BusinessRuleException("Re-routing " + id + " gives the same approvers; nothing to change");
        int seq = steps.findByInvoiceIdAndHistoricalFalseOrderBySeq(id).stream().mapToInt(ApprovalStep::getSeq).max().orElse(0);
        List<String> changes = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            ApprovalStep o = pending.get(i);
            ApprovalRouter.Step n = r.steps().get(i);
            o.setDecision("CANCELLED");
            steps.save(o);
            ApprovalStep row = new ApprovalStep();
            row.setInvoiceId(id);
            row.setSeq(++seq);
            row.setRequiredRole(n.role());
            row.setAssignedUserId(n.assigneeId());
            row.setOnBehalfOfUserId(n.onBehalfOfId());
            row.setPolicyIds(o.getPolicyIds());
            row.setRoutingNotes(String.join("\n", n.notes().isEmpty() ? List.of("Re-routed: " + reason) : n.notes()));
            row.setDecision("PENDING");
            steps.save(row);
            changes.add(n.role() + ": " + o.getAssignedUserId() + " -> " + n.assigneeId() + (n.onBehalfOfId() == null ? "" : " on behalf of " + n.onBehalfOfId()));
        }
        return workflow.recordChangeOnBehalf(inv, "APPROVAL_REROUTED", null, Map.of("changes", changes), "Re-routed by " + u.id() + ": " + reason
                + " (" + String.join("; ", changes) + ")");
    }

    // ---------- queues ----------

    public record QueueItem(String invoiceId, String vendorName, String vendorInvoiceNumber, BigDecimal totalCad, BigDecimal routedAmountCad,
                            String currency, BigDecimal total, int stepSeq, int stepCount, String role, String assignedUserId, String onBehalfOfUserId,
                            boolean asDelegate, String topFlag, long daysWaiting, String departmentId, String requesterUserId) {}

    @Transactional(readOnly = true)
    public List<QueueItem> mine() {
        CurrentUser u = Auth.require();
        Set<String> ids = access.actingIds(u.id());
        Map<String, String> vendorNames = vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, Vendor::getLegalName));
        List<QueueItem> out = new ArrayList<>();
        for (Invoice inv : invoices.findByStatus(InvoiceStatus.PENDING_APPROVAL)) {
            List<ApprovalStep> chain = currentRound(inv.getId());
            ApprovalStep cur = chain.stream().filter(s -> "PENDING".equals(s.getDecision())).findFirst().orElse(null);
            if (cur == null || !ids.contains(cur.getAssignedUserId())) continue;
            Flag top = flags.findByInvoiceIdAndStatus(inv.getId(), "OPEN").stream().min(Comparator.comparing(Flag::getSeverity)).orElse(null);
            java.time.Instant since = chain.stream().filter(s -> s.getActedAt() != null).map(ApprovalStep::getActedAt).max(Comparator.naturalOrder())
                    .orElse(inv.getUpdatedAt());
            out.add(new QueueItem(inv.getId(), inv.getVendorId() == null ? inv.getVendorNameRaw() : vendorNames.get(inv.getVendorId()),
                    inv.getVendorInvoiceNumber(), inv.getTotalCad(), inv.getRoutedAmountCad(), inv.getCurrency(), inv.getTotal(),
                    chain.indexOf(cur) + 1, chain.size(), // position in the current round (seq keeps counting across rounds)
                    cur.getRequiredRole(), cur.getAssignedUserId(), cur.getOnBehalfOfUserId(), !u.id().equals(cur.getAssignedUserId()),
                    top == null ? null : top.getTitle(),
                    since == null ? 0 : ChronoUnit.DAYS.between(since, clock.now()), inv.getDepartmentId(), inv.getRequesterUserId()));
        }
        out.sort(Comparator.comparingLong(QueueItem::daysWaiting).reversed());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(Flag f) {
        try {
            return f.getEvidenceJson() == null ? Map.of() : Json.read(f.getEvidenceJson(), Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
