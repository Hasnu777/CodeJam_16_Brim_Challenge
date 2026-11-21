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

    public Invoice load(String id) {
        return invoices.findById(id).orElseThrow(() -> new NotFoundException("Invoice " + id));
    }

    /** Low-level transition used by every higher-level operation. Must run inside a transaction. */
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
