package com.brimap.workflow;

import com.brimap.audit.AuditService;
import com.brimap.auth.Auth;
import com.brimap.auth.CurrentUser;
import com.brimap.auth.ForbiddenException;
import com.brimap.common.BusinessRuleException;
import com.brimap.common.NotFoundException;
import com.brimap.config.AppClock;
import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.model.Role;
import com.brimap.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single entry point for invoice state changes. Every method: (1) checks the caller may do this,
 * (2) checks the transition is legal, (3) writes the audit event, (4) saves - all in one transaction.
 * Controllers never save an Invoice directly. Later phases add submit/approve/reject/schedule here, built on
 * {@link #transition}.
 */
@Service
@RequiredArgsConstructor
public class InvoiceWorkflowService {
    private final InvoiceRepository invoices;
    private final AuditService audit;
    private final AppClock clock;
    private final com.brimap.repository.InvoiceLineRepository lines;
    private final com.brimap.repository.InvoiceTaxRepository taxes;
    private final com.brimap.repository.FlagRepository flags;
    private final com.brimap.repository.ApprovalStepRepository steps;
    private final com.brimap.repository.ExtractionRepository extractions;
    private final com.brimap.repository.PaymentRepository payments;
    private final com.brimap.repository.EmailMessageRepository emails;

    /** Statuses a bill can be deleted in: not yet approved, and never anything with money attached (D-101). */
    public static final java.util.Set<InvoiceStatus> DELETABLE = java.util.EnumSet.of(
            InvoiceStatus.RECEIVED, InvoiceStatus.EXTRACTED, InvoiceStatus.IN_REVIEW, InvoiceStatus.DUPLICATE, InvoiceStatus.REJECTED);

    public Invoice load(String id) {
        return invoices.findById(id).orElseThrow(() -> new NotFoundException("Invoice " + id));
    }

    /** Low-level transition used by every higher-level operation. Must run inside a transaction. */
    /** Create a new bill in RECEIVED (intake/upload). The only way a non-historical invoice comes into existence. */
    @Transactional
    public Invoice create(Invoice inv, String detail) {
        if (inv.getStatus() == null) inv.setStatus(InvoiceStatus.RECEIVED);
        if (inv.getStatus() != InvoiceStatus.RECEIVED) throw new BusinessRuleException("New invoices start in RECEIVED");
        inv.setCreatedAt(clock.now());
        inv.setUpdatedAt(inv.getCreatedAt());
        Invoice saved = invoices.save(inv);
        audit.record("INVOICE_RECEIVED", "invoice", saved.getId(), null, snapshot(saved), detail);
        return saved;
    }

    @Transactional
    public Invoice transition(Invoice inv, InvoiceStatus to, String action, String detail) {
        return transition(inv, to, action, detail, null);
    }

    @Transactional
    public Invoice transition(Invoice inv, InvoiceStatus to, String action, String detail, String onBehalfOf) {
        InvoiceStatus from = inv.getStatus();
        if (inv.isHistorical() && to != InvoiceStatus.SCHEDULED && to != InvoiceStatus.RELEASED && to != InvoiceStatus.PAID
                && to != InvoiceStatus.APPROVED) {
            throw new BusinessRuleException(inv.getId() + " is a historical record and cannot be re-processed");
        }
        if (!InvoiceStateMachine.canTransition(from, to)) {
            throw new BusinessRuleException("Invoice " + inv.getId() + " cannot move from " + from + " to " + to);
        }
        Map<String, Object> before = snapshot(inv);
        if (to == InvoiceStatus.ON_HOLD && from != InvoiceStatus.ON_HOLD) inv.setStatusBeforeHold(from);
        inv.setStatus(to);
        inv.setUpdatedAt(clock.now());
        Invoice saved = invoices.save(inv);
        if (onBehalfOf != null) audit.recordOnBehalf(onBehalfOf, action, "invoice", inv.getId(), before, snapshot(saved), detail);
        else audit.record(action, "invoice", inv.getId(), before, snapshot(saved), detail);
        return saved;
    }

    /** Record a non-status change to an invoice (field edit, coding change) with before/after. */
    @Transactional
    public Invoice recordChange(Invoice inv, String action, Map<String, Object> before, Map<String, Object> after, String detail) {
        inv.setUpdatedAt(clock.now());
        Invoice saved = invoices.save(inv);
        audit.record(action, "invoice", inv.getId(), before, after, detail);
        return saved;
    }

    /** A non-status change done by a delegate: the audit row says "X on behalf of Y". */
    @Transactional
    public Invoice recordChangeOnBehalf(Invoice inv, String action, String onBehalfOf, Map<String, Object> after, String detail) {
        inv.setUpdatedAt(clock.now());
        Invoice saved = invoices.save(inv);
        if (onBehalfOf != null) audit.recordOnBehalf(onBehalfOf, action, "invoice", inv.getId(), null, after, detail);
        else audit.record(action, "invoice", inv.getId(), null, after, detail);
        return saved;
    }

    /** Manual hold: CLK, FM, ADM. */
    @Transactional
    public Invoice hold(String id, String reason) {
        CurrentUser u = Auth.require();
        requireRole(u, "place a hold", Role.CLK, Role.FM, Role.ADM);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A hold needs a reason");
        Invoice inv = load(id);
        if (inv.getStatus() == InvoiceStatus.ON_HOLD) throw new BusinessRuleException(id + " is already on hold");
        inv.setHoldReason(reason);
        return transition(inv, InvoiceStatus.ON_HOLD, "HOLD_PLACED", reason);
    }

    /** Lift a manual hold, returning the invoice to where it was. */
    @Transactional
    public Invoice releaseHold(String id, String note) {
        CurrentUser u = Auth.require();
        requireRole(u, "lift a hold", Role.CLK, Role.FM, Role.ADM);
        Invoice inv = load(id);
        if (inv.getStatus() != InvoiceStatus.ON_HOLD) throw new BusinessRuleException(id + " is not on hold");
        InvoiceStatus back = inv.getStatusBeforeHold() == null ? InvoiceStatus.IN_REVIEW : inv.getStatusBeforeHold();
        inv.setHoldReason(null);
        inv.setStatusBeforeHold(null);
        return transition(inv, back, "HOLD_RELEASED", note);
    }

    /**
     * Delete a bill that should never have existed (a duplicate upload, the wrong file). EMP may delete only their own
     * uploads; CLK and ADM any. The bill and its lines, taxes, flags, approval rounds and extraction runs are removed;
     * the audit log keeps an INVOICE_DELETED row with the bill's last snapshot and the reason (D-101).
     */
    @Transactional
    public void delete(String id, String reason) {
        CurrentUser u = Auth.require();
        requireRole(u, "delete invoices", Role.EMP, Role.CLK, Role.ADM);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Say why the invoice is being deleted");
        Invoice inv = load(id);
        if (u.role() == Role.EMP && !u.id().equals(inv.getRequesterUserId())) throw new ForbiddenException("You can only delete invoices you uploaded");
        if (inv.isHistorical()) throw new BusinessRuleException(id + " is a historical record and cannot be deleted");
        if (!DELETABLE.contains(inv.getStatus()))
            throw new BusinessRuleException(id + " is " + inv.getStatus() + "; only bills that are not yet approved can be deleted");
        if (!payments.findByInvoiceId(id).isEmpty()) throw new BusinessRuleException(id + " has a payment and cannot be deleted");
        Map<String, Object> before = snapshot(inv);
        lines.deleteAll(lines.findByInvoiceIdOrderByLineNo(id));
        taxes.deleteAll(taxes.findByInvoiceId(id));
        flags.deleteAll(flags.findByInvoiceId(id));
        steps.deleteAll(steps.findByInvoiceIdOrderBySeq(id));
        extractions.deleteAll(extractions.findByInvoiceIdOrderByCreatedAtDesc(id));
        if (inv.getEmailId() != null) emails.findById(inv.getEmailId()).ifPresent(e -> {
            if (e.getLinkedInvoiceIds() == null) return;
            String rest = java.util.Arrays.stream(e.getLinkedInvoiceIds().split(",")).filter(x -> !x.equals(id)).collect(java.util.stream.Collectors.joining(","));
            e.setLinkedInvoiceIds(rest.isEmpty() ? null : rest);
            emails.save(e);
        });
        invoices.delete(inv);
        audit.record("INVOICE_DELETED", "invoice", id, before, null, reason.trim());
        // The caller re-validates open bills: one marked as a duplicate of this bill goes back to review (ValidationService).
    }

    public static void requireRole(CurrentUser u, String what, Role... roles) {
        if (!u.is(roles)) throw new ForbiddenException("Role " + u.role() + " cannot " + what);
    }

    public static Map<String, Object> snapshot(Invoice i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", i.getStatus());
        m.put("vendorId", i.getVendorId());
        m.put("vendorInvoiceNumber", i.getVendorInvoiceNumber());
        m.put("invoiceDate", i.getInvoiceDate());
        m.put("dueDate", i.getDueDate());
        m.put("currency", i.getCurrency());
        m.put("subtotal", i.getSubtotal());
        m.put("total", i.getTotal());
        m.put("totalCad", i.getTotalCad());
        m.put("departmentId", i.getDepartmentId());
        m.put("poId", i.getPoId());
        m.put("holdReason", i.getHoldReason());
        return m;
    }
}
