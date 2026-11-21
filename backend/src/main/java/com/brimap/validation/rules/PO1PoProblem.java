package com.brimap.validation.rules;

import com.brimap.model.Invoice;
import com.brimap.model.PurchaseOrder;
import com.brimap.validation.FlagDraft;
import com.brimap.validation.InvoiceContext;
import com.brimap.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;

/** PO1: referenced PO not found, closed, or belongs to another vendor. */
@Component
public class PO1PoProblem implements ValidationRule {
    public String code() { return "PO1"; }

    public List<FlagDraft> check(InvoiceContext ctx) {
        Invoice i = ctx.invoice();
        if (i.getPoId() == null) return List.of();
        PurchaseOrder po = ctx.po();
        if (po == null) return List.of(flag("PO " + i.getPoId() + " is not in the PO register", "poId", i.getPoId()));
        if ("closed".equalsIgnoreCase(po.getStatus())) {
            return List.of(flag(po.getId() + " is closed (" + po.getDescription() + ")", "poId", po.getId(), "status", po.getStatus()));
        }
        if (i.getVendorId() != null && !i.getVendorId().equals(po.getVendorId())) {
            return List.of(flag(po.getId() + " was issued to vendor " + po.getVendorId() + ", not " + i.getVendorId(),
                    "poId", po.getId(), "poVendorId", po.getVendorId(), "invoiceVendorId", i.getVendorId()));
        }
        return List.of();
    }
}
