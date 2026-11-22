package com.brimap.workflow;

import com.brimap.audit.AuditService;
import com.brimap.auth.Auth;
import com.brimap.auth.CurrentUser;
import com.brimap.auth.ForbiddenException;
import com.brimap.coding.CodingService;
import com.brimap.common.BusinessRuleException;
import com.brimap.common.NotFoundException;
import com.brimap.config.AppClock;
import com.brimap.duplicates.InvoiceNumbers;
import com.brimap.extraction.InvoiceAssembler;
import com.brimap.model.*;
import com.brimap.repository.*;
import com.brimap.validation.ValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AP clerk review: correct header fields, accept/override line coding, resolve flags. Every change goes through
 * the workflow service's audited recordChange, and field edits re-run coding + validation.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {
    private final InvoiceWorkflowService workflow;
    private final InvoiceLineRepository lines;
    private final FlagRepository flags;
    private final VendorRepository vendors;
    private final DepartmentRepository departments;
    private final GlAccountRepository glAccounts;
    private final PurchaseOrderRepository pos;
    private final InvoiceAssembler assembler;
    private final CodingService coding;
    private final ValidationService validation;
    private final AuditService audit;
    private final AppClock clock;

    public record HeaderUpdate(String vendorId, String vendorInvoiceNumber, LocalDate invoiceDate, LocalDate dueDate, String currency,
                               BigDecimal subtotal, BigDecimal gstHstAmount, BigDecimal pstQstAmount, BigDecimal total, String departmentId,
                               String requesterUserId, String poId, String shipToProvince, String remitAccount) {}

    @Transactional
    public Invoice updateHeader(String id, HeaderUpdate u) {
        Invoice inv = editable(id);
        Map<String, Object> before = InvoiceWorkflowService.snapshot(inv);
        before.put("shipToProvince", inv.getShipToProvince());
        before.put("remitAccount", inv.getRemitAccount());
        if (u.vendorId() != null) {
            vendors.findById(u.vendorId()).orElseThrow(() -> new NotFoundException("Vendor " + u.vendorId()));
            inv.setVendorId(u.vendorId());
            inv.setVendorMatchMethod("MANUAL");
        }
        if (u.vendorInvoiceNumber() != null) {
            inv.setVendorInvoiceNumber(u.vendorInvoiceNumber().trim());
            inv.setNormalizedInvoiceNumber(InvoiceNumbers.normalize(inv.getVendorInvoiceNumber()));
        }
        if (u.invoiceDate() != null) inv.setInvoiceDate(u.invoiceDate());
        if (u.dueDate() != null) inv.setDueDate(u.dueDate());
        if (u.currency() != null) inv.setCurrency(u.currency().toUpperCase());
        if (u.subtotal() != null) inv.setSubtotal(u.subtotal().setScale(2, RoundingMode.HALF_UP));
        if (u.gstHstAmount() != null) inv.setGstHstAmount(u.gstHstAmount().setScale(2, RoundingMode.HALF_UP));
        if (u.pstQstAmount() != null) inv.setPstQstAmount(u.pstQstAmount().setScale(2, RoundingMode.HALF_UP));
        if (u.total() != null) inv.setTotal(u.total().setScale(2, RoundingMode.HALF_UP));
        if (u.departmentId() != null) {
            departments.findById(u.departmentId()).orElseThrow(() -> new NotFoundException("Department " + u.departmentId()));
            inv.setDepartmentId(u.departmentId());
        }
        if (u.requesterUserId() != null) inv.setRequesterUserId(u.requesterUserId());
        if (u.poId() != null) {
            String po = u.poId().isBlank() ? null : u.poId().trim();
            inv.setPoId(po);
            if (po != null) pos.findById(po).ifPresent(p -> {
                if (u.departmentId() == null) inv.setDepartmentId(p.getDepartmentId());
                if (u.requesterUserId() == null) inv.setRequesterUserId(p.getRequesterUserId());
            });
        }
        if (u.shipToProvince() != null) inv.setShipToProvince(u.shipToProvince().toUpperCase());
        if (u.remitAccount() != null) inv.setRemitAccount(u.remitAccount().isBlank() ? null : u.remitAccount().replaceAll("\\D", ""));
        inv.setCreditNote(inv.getTotal() != null && inv.getTotal().signum() < 0 || inv.isCreditNote() && u.total() == null);
        assembler.applyFx(inv);
        // Fields the clerk confirmed are no longer "low confidence".
        inv.setLowConfidenceFields(null);
        Map<String, Object> after = InvoiceWorkflowService.snapshot(inv);
        after.put("shipToProvince", inv.getShipToProvince());
        after.put("remitAccount", inv.getRemitAccount());
        Invoice saved = workflow.recordChange(inv, "FIELDS_CORRECTED", before, after, "Header corrected by AP clerk");
        coding.process(saved);
        validation.validate(saved);
        return workflow.load(id);
    }

    /**
     * Link a purchase order picked by the clerk (usually from the PO suggestions next to a P13 flag). Only the PO
     * changes: department and requester are filled from the PO only when blank, so an employee's own upload keeps its
     * requester. Re-validation clears P13 (and raises PO1/P12 if the PO is closed, another vendor's, or overspent).
     */
    @Transactional
    public Invoice linkPo(String id, String poId) {
        Invoice inv = editable(id);
        if (poId == null || poId.isBlank()) throw new IllegalArgumentException("poId is required");
        var po = pos.findById(poId.trim()).orElseThrow(() -> new NotFoundException("PO " + poId));
        if (po.getId().equals(inv.getPoId())) throw new BusinessRuleException(id + " is already linked to " + po.getId());
        Map<String, Object> before = InvoiceWorkflowService.snapshot(inv);
        inv.setPoId(po.getId());
        if (inv.getDepartmentId() == null) inv.setDepartmentId(po.getDepartmentId());
        if (inv.getRequesterUserId() == null) inv.setRequesterUserId(po.getRequesterUserId());
        Invoice saved = workflow.recordChange(inv, "PO_LINKED", before, InvoiceWorkflowService.snapshot(inv),
                "Linked " + po.getId() + " (" + po.getDescription() + ")");
        validation.validate(saved);
        return workflow.load(id);
    }

    public record LineUpdate(String glAccount, String departmentId, String gstHstCode, String pstQstCode, String lineType) {}

    /** Accept or override the GL/department suggestion. A change away from the suggestion is logged as an override. */
    @Transactional
    public InvoiceLine updateLine(String id, int lineNo, LineUpdate u) {
        Invoice inv = editable(id);
        InvoiceLine l = lines.findByInvoiceIdOrderByLineNo(id).stream().filter(x -> x.getLineNo() == lineNo).findFirst()
                .orElseThrow(() -> new NotFoundException("Line " + lineNo + " of " + id));
        Map<String, Object> before = lineSnapshot(l);
        if (u.glAccount() != null) {
            glAccounts.findById(u.glAccount()).orElseThrow(() -> new NotFoundException("GL account " + u.glAccount()));
            l.setGlAccount(u.glAccount());
        }
        if (u.departmentId() != null) {
            departments.findById(u.departmentId()).orElseThrow(() -> new NotFoundException("Department " + u.departmentId()));
            l.setDepartmentId(u.departmentId());
        }
        if (u.gstHstCode() != null) l.setGstHstCode(u.gstHstCode().isBlank() ? null : u.gstHstCode());
        if (u.pstQstCode() != null) l.setPstQstCode(u.pstQstCode().isBlank() ? null : u.pstQstCode());
        if (u.lineType() != null) l.setLineType(u.lineType());
        boolean overridden = (l.getSuggestedGlAccount() != null && !l.getSuggestedGlAccount().equals(l.getGlAccount()))
                || (l.getSuggestedDepartmentId() != null && !l.getSuggestedDepartmentId().equals(l.getDepartmentId()));
        l.setSuggestionOverridden(overridden);
        l.setCodingConfirmed(l.getGlAccount() != null);
        lines.save(l);
        String action = overridden ? "CODING_SUGGESTION_OVERRIDDEN" : "CODING_ACCEPTED";
        String detail = overridden
                ? "Line " + lineNo + ": suggested " + l.getSuggestedGlAccount() + "/" + l.getSuggestedDepartmentId() + ", clerk chose "
                + l.getGlAccount() + "/" + l.getDepartmentId() + " (suggestion reason: " + l.getSuggestionReason() + ")"
                : "Line " + lineNo + ": suggestion " + l.getGlAccount() + "/" + l.getDepartmentId() + " accepted";
        workflow.recordChange(inv, action, before, lineSnapshot(l), detail);
        return l;
    }

    @Transactional
    public Invoice acceptAllCoding(String id) {
        Invoice inv = editable(id);
        int n = 0;
        for (InvoiceLine l : lines.findByInvoiceIdOrderByLineNo(id)) {
            // Keep a coding the clerk picked by hand; refill lines that were cleared with Ignore Suggestions.
            if (l.getSuggestedGlAccount() == null || (l.isSuggestionOverridden() && l.getGlAccount() != null)) continue;
            l.setGlAccount(l.getSuggestedGlAccount());
            l.setDepartmentId(l.getSuggestedDepartmentId());
            l.setSuggestionOverridden(false);
            l.setCodingConfirmed(true);
            lines.save(l);
            n++;
        }
        return workflow.recordChange(inv, "CODING_ACCEPTED", null, Map.of("lines", n), n + " line suggestions accepted");
    }

    /** Ignore Suggestions: clear every line's GL account and department so the clerk codes them by hand (D-107). */
    @Transactional
    public Invoice ignoreCoding(String id) {
        Invoice inv = editable(id);
        List<Map<String, Object>> cleared = new ArrayList<>();
        for (InvoiceLine l : lines.findByInvoiceIdOrderByLineNo(id)) {
            if (l.getGlAccount() == null && l.getDepartmentId() == null) continue;
            cleared.add(lineSnapshot(l));
            l.setGlAccount(null);
            l.setDepartmentId(null);
            l.setSuggestionOverridden(true); // re-running coding (after Save Fields) must not refill them
            l.setCodingConfirmed(false);
            lines.save(l);
        }
        return workflow.recordChange(inv, "CODING_SUGGESTIONS_IGNORED", Map.of("lines", cleared), null,
                cleared.size() + " line suggestions ignored; GL accounts and departments cleared");
    }

    public record FlagResolution(String resolution, String note) {}

    private static final Set<String> RESOLUTIONS = Set.of("ACKNOWLEDGED", "RESOLVED", "OVERRIDDEN");

    /**
     * WARN/INFO: the clerk (or FM/ADM) acknowledges or resolves. BLOCK: only a Finance Manager or Admin may override,
     * with a note. The flag row keeps who, when and why.
     */
    @Transactional
    public Flag resolveFlag(long flagId, FlagResolution r) {
        CurrentUser u = Auth.require();
        Flag f = flags.findById(flagId).orElseThrow(() -> new NotFoundException("Flag " + flagId));
        Invoice inv = workflow.load(f.getInvoiceId());
        if (!f.isOpen()) throw new BusinessRuleException("Flag " + flagId + " is already " + f.getStatus());
        String res = r.resolution() == null ? (f.getSeverity() == Severity.BLOCK ? "OVERRIDDEN" : "ACKNOWLEDGED") : r.resolution().toUpperCase();
        if (!RESOLUTIONS.contains(res)) throw new IllegalArgumentException("Resolution must be one of " + RESOLUTIONS);
        if (f.getSeverity() == Severity.BLOCK && !u.is(Role.FM, Role.ADM)) {
            throw new ForbiddenException(f.getRuleCode() + " is a BLOCK flag; only a Finance Manager or Controller can override it");
        }
        if (f.getSeverity() == Severity.BLOCK && (r.note() == null || r.note().isBlank())) {
            throw new IllegalArgumentException("Overriding a BLOCK flag needs a note explaining why");
        }
        Map<String, Object> before = Map.of("status", f.getStatus());
        f.setStatus(res);
        f.setResolution(res);
        f.setResolvedBy(u.id());
        f.setResolvedAt(clock.now());
        f.setNote(r.note());
        flags.save(f);
        audit.record("FLAG_" + res, "invoice", inv.getId(), before,
                Map.of("flagId", flagId, "rule", f.getRuleCode(), "status", res),
                f.getRuleCode() + " (" + f.getSeverity() + "): " + (r.note() == null ? "" : r.note()));
        return f;
    }

    private Invoice editable(String id) {
        CurrentUser u = Auth.require();
        Invoice inv = workflow.load(id);
        if (inv.isHistorical()) throw new BusinessRuleException(id + " is a historical record");
        if (!InvoiceStateMachine.editable(inv.getStatus())) {
            throw new BusinessRuleException(id + " is " + inv.getStatus() + "; fields can only be changed before submission");
        }
        if (!u.is(Role.CLK, Role.ADM)) throw new ForbiddenException("Only the AP clerk can correct invoice fields");
        return inv;
    }

    private static Map<String, Object> lineSnapshot(InvoiceLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineNo", l.getLineNo());
        m.put("glAccount", l.getGlAccount());
        m.put("departmentId", l.getDepartmentId());
        m.put("gstHstCode", l.getGstHstCode());
        m.put("pstQstCode", l.getPstQstCode());
        m.put("lineType", l.getLineType());
        return m;
    }
}
