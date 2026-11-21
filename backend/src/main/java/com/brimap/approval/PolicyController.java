package com.brimap.approval;

import com.brimap.audit.AuditService;
import com.brimap.common.NotFoundException;
import com.brimap.model.ApprovalPolicy;
import com.brimap.model.Invoice;
import com.brimap.repository.ApprovalPolicyRepository;
import com.brimap.repository.InvoiceRepository;
import com.brimap.validation.RevalidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** The approval/payment rules as data. Admin edits take effect immediately for new submissions. */
@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {
    private static final Set<String> KINDS = Set.of("approval", "payment_release", "match", "assignment");
    private static final Set<String> ROLES = Set.copyOf(ApprovalRouter.ROLE_ORDER);

    private final ApprovalPolicyRepository policies;
    private final InvoiceRepository invoices;
    private final ApprovalService approvals;
    private final AuditService audit;

    @GetMapping
    @PreAuthorize("hasAnyRole('FM','ADM','AUD')")
    public List<ApprovalPolicy> list() { return policies.findAllByOrderByPriorityAsc(); }

    /** Replace the whole rule table (validated first; any error rejects the whole save). */
    @PutMapping
    @PreAuthorize("hasRole('ADM')")
    @Transactional
    public List<ApprovalPolicy> save(@RequestBody List<ApprovalPolicy> rows) {
        validate(rows);
        List<ApprovalPolicy> before = policies.findAllByOrderByPriorityAsc();
        Map<String, ApprovalPolicy> old = new HashMap<>();
        before.forEach(p -> old.put(p.getId(), p));
        policies.deleteAll(before.stream().filter(p -> rows.stream().noneMatch(r -> r.getId().equals(p.getId()))).toList());
        for (ApprovalPolicy r : rows) {
            ApprovalPolicy p = policies.findById(r.getId()).orElseGet(ApprovalPolicy::new);
            p.setId(r.getId());
            p.setKind(r.getKind());
            p.setPriority(r.getPriority());
            p.setConditionJson(r.getConditionJson());
            p.setAddsRoles(r.getAddsRoles() == null ? "" : r.getAddsRoles().replace(" ", ""));
            p.setEnabled(r.isEnabled());
            p.setNotes(r.getNotes());
            if (r.getSourceText() != null) p.setSourceText(r.getSourceText());
            policies.save(p);
        }
        audit.record("POLICIES_UPDATED", "approval_policies", "all", summary(before), summary(rows), rows.size() + " rules saved");
        // New thresholds also feed the split detector (S01) the next time each bill is validated (edit, submit, batch).
        return policies.findAllByOrderByPriorityAsc();
    }

    public record PreviewRequest(String invoiceId, List<ApprovalPolicy> policies) {}

    /** "Who would approve this invoice under these (possibly unsaved) rules?" */
    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ADM','FM')")
    public Map<String, Object> preview(@RequestBody PreviewRequest req) {
        Invoice inv = invoices.findById(req.invoiceId()).orElseThrow(() -> new NotFoundException("Invoice " + req.invoiceId()));
        List<ApprovalPolicy> rows = req.policies() == null || req.policies().isEmpty() ? policies.findAllByOrderByPriorityAsc() : req.policies();
        validate(rows);
        ApprovalRouter.Result current = approvals.preview(inv, policies.findAllByOrderByPriorityAsc());
        ApprovalRouter.Result edited = approvals.preview(inv, rows);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoiceId", inv.getId());
        m.put("current", current);
        m.put("withEdits", edited);
        return m;
    }

    static void validate(List<ApprovalPolicy> rows) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("At least one rule is required");
        Set<String> ids = new HashSet<>();
        for (ApprovalPolicy p : rows) {
            String where = "Rule " + (p.getId() == null ? "(no id)" : p.getId()) + ": ";
            if (p.getId() == null || p.getId().isBlank()) throw new IllegalArgumentException(where + "id is required");
            if (!ids.add(p.getId())) throw new IllegalArgumentException(where + "duplicate id");
            if (!KINDS.contains(p.getKind())) throw new IllegalArgumentException(where + "kind must be one of " + KINDS);
            try {
                PolicyConditionEvaluator.validate(p.getConditionJson());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + e.getMessage());
            }
            if (p.getAddsRoles() != null && !p.getAddsRoles().isBlank()) {
                for (String r : p.getAddsRoles().split(",")) {
                    if (!ROLES.contains(r.trim())) throw new IllegalArgumentException(where + "unknown role '" + r.trim() + "'; use " + ROLES);
                }
            }
        }
        boolean hasApproval = rows.stream().anyMatch(p -> p.isEnabled() && "approval".equals(p.getKind()));
        if (!hasApproval) throw new IllegalArgumentException("At least one enabled approval rule is required");
    }

    private static List<String> summary(List<ApprovalPolicy> rows) {
        return rows.stream().map(p -> p.getId() + (p.isEnabled() ? "" : "(off)") + " " + p.getKind() + " " + p.getConditionJson() + " -> " + p.getAddsRoles()).toList();
    }
}
