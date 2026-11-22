package com.brimap.validation;

import com.brimap.audit.AuditService;
import com.brimap.common.Json;
import com.brimap.config.AppClock;
import com.brimap.intake.InvoiceProcessingStep;
import com.brimap.model.*;
import com.brimap.repository.FlagRepository;
import com.brimap.repository.InvoiceRepository;
import com.brimap.workflow.InvoiceWorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Runs every ValidationRule and reconciles the findings with the invoice's existing flags:
 *  - a finding identical to an existing flag (same code + message) keeps that flag, including its resolution;
 *  - an OPEN flag whose condition no longer holds is marked RESOLVED "AUTO_CLEARED" (never deleted);
 *  - new findings become OPEN flags.
 * Exact duplicates (D01/D03) move a freshly extracted invoice to DUPLICATE.
 */
@Service
@Slf4j
public class ValidationService implements InvoiceProcessingStep {
    private final List<ValidationRule> rules;
    private final InvoiceContextFactory contexts;
    private final FlagRepository flags;
    private final InvoiceRepository invoices;
    private final AuditService audit;
    private final InvoiceWorkflowService workflow;
    private final AppClock clock;

    public ValidationService(List<ValidationRule> rules, InvoiceContextFactory contexts, FlagRepository flags, InvoiceRepository invoices,
                             AuditService audit, InvoiceWorkflowService workflow, AppClock clock) {
        this.rules = rules.stream().sorted(Comparator.comparing(ValidationRule::code)).toList();
        this.contexts = contexts; this.flags = flags; this.invoices = invoices; this.audit = audit; this.workflow = workflow; this.clock = clock;
    }

    @Override
    public int order() { return 20; }

    @Override
    public void process(Invoice invoice) { validate(invoice); }

    public List<ValidationRule> rules() { return rules; }

    /** Pure evaluation (no persistence): used by tests and the policy preview. */
    public List<FlagDraft> evaluate(InvoiceContext ctx) {
        List<FlagDraft> out = new ArrayList<>();
        for (ValidationRule r : rules) {
            try {
                out.addAll(r.check(ctx));
            } catch (RuntimeException e) {
                log.error("Rule {} failed on {}", r.code(), ctx.invoice().getId(), e);
                out.add(new FlagDraft("X03", Severity.WARN, "Rule error", "Rule " + r.code() + " could not run (" + e.getClass().getSimpleName()
                        + "); check this invoice manually", Map.of("rule", r.code())));
            }
        }
        return out;
    }

    @Transactional
    public List<Flag> validate(Invoice invoice) {
        Invoice inv = invoices.findById(invoice.getId()).orElseThrow();
        List<FlagDraft> drafts = evaluate(contexts.build(inv));
        List<Flag> existing = flags.findByInvoiceId(inv.getId());
        Map<String, Flag> byKey = new HashMap<>();
        for (Flag f : existing) byKey.putIfAbsent(key(f.getRuleCode(), f.getMessage()), f);

        Set<String> seen = new HashSet<>();
        List<Flag> created = new ArrayList<>();
        for (FlagDraft d : drafts) {
            String k = key(d.code(), d.message());
            if (!seen.add(k)) continue;
            Flag prior = byKey.get(k);
            if (prior != null && !"AUTO_CLEARED".equals(prior.getResolution())) continue; // unchanged; keep its state and resolution
            Flag f = new Flag();
            f.setInvoiceId(inv.getId());
            f.setRuleCode(d.code());
            f.setSeverity(d.severity());
            f.setTitle(d.title());
            f.setMessage(d.message());
            f.setEvidenceJson(Json.write(d.evidence()));
            f.setStatus("OPEN");
            f.setCreatedAt(clock.now());
            created.add(flags.save(f));
        }
        List<String> cleared = new ArrayList<>();
        for (Flag f : existing) {
            if (f.isOpen() && !seen.contains(key(f.getRuleCode(), f.getMessage()))) {
                f.setStatus("RESOLVED");
                f.setResolution("AUTO_CLEARED");
                f.setResolvedBy("system");
                f.setResolvedAt(clock.now());
                f.setNote("Condition no longer holds after re-validation");
                cleared.add(f.getRuleCode());
            }
        }
        List<Flag> open = flags.findByInvoiceId(inv.getId()).stream().filter(Flag::isOpen).toList();
        Map<Severity, Long> bySev = open.stream().collect(Collectors.groupingBy(Flag::getSeverity, () -> new EnumMap<>(Severity.class), Collectors.counting()));
        audit.record("VALIDATION_RUN", "invoice", inv.getId(), null,
                Map.of("open", open.stream().map(Flag::getRuleCode).sorted().toList(), "new", created.stream().map(Flag::getRuleCode).toList(),
                        "autoCleared", cleared),
                rules.size() + " rules: " + bySev.getOrDefault(Severity.BLOCK, 0L) + " block, " + bySev.getOrDefault(Severity.WARN, 0L) + " warn, "
                        + bySev.getOrDefault(Severity.INFO, 0L) + " info open");

        boolean exactDuplicate = open.stream().anyMatch(f -> "D01".equals(f.getRuleCode()) || "D03".equals(f.getRuleCode()));
        if (exactDuplicate && (inv.getStatus() == InvoiceStatus.EXTRACTED || inv.getStatus() == InvoiceStatus.IN_REVIEW)) {
            workflow.transition(inv, InvoiceStatus.DUPLICATE, "MARKED_DUPLICATE",
                    open.stream().filter(f -> f.getRuleCode().startsWith("D0")).map(Flag::getMessage).findFirst().orElse("Exact duplicate"));
        }
        return open;
    }

    /**
     * Approved and scheduled bills are not re-validated (their review is over), but their payment holds (V01, V02, B01,
     * B02) follow the vendor's live state: a hold raised during approval must clear once the vendor is approved or the
     * bank change is verified, or the payment stays stuck. Clears hold flags whose rule no longer fires at all (D-088).
     * Never adds flags: release checks the vendor, its bank account and pending bank changes directly (ReleaseRules).
     */
    @Transactional
    public List<String> clearStaleHolds(Invoice invoice) {
        Invoice inv = invoices.findById(invoice.getId()).orElseThrow();
        Set<String> stillFiring = evaluate(contexts.build(inv)).stream().map(FlagDraft::code)
                .filter(com.brimap.approval.ApprovalService.PAYMENT_HOLD_CODES::contains).collect(Collectors.toSet());
        List<String> cleared = new ArrayList<>();
        for (Flag f : flags.findByInvoiceId(inv.getId())) {
            if (!f.isOpen() || !com.brimap.approval.ApprovalService.PAYMENT_HOLD_CODES.contains(f.getRuleCode()) || stillFiring.contains(f.getRuleCode())) continue;
            f.setStatus("RESOLVED");
            f.setResolution("AUTO_CLEARED");
            f.setResolvedBy("system");
            f.setResolvedAt(clock.now());
            f.setNote("Vendor details changed; this payment hold no longer applies");
            flags.save(f);
            cleared.add(f.getRuleCode());
        }
        if (!cleared.isEmpty()) {
            audit.record("PAYMENT_HOLD_CLEARED", "invoice", inv.getId(), null, Map.of("autoCleared", cleared),
                    "Payment hold " + String.join(", ", cleared) + " cleared: the vendor's details no longer trigger it");
        }
        return cleared;
    }

    private static String key(String code, String message) { return code + "|" + message; }
}
