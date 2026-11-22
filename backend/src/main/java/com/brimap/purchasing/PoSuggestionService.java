package com.brimap.purchasing;

import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.model.PurchaseOrder;
import com.brimap.repository.InvoiceRepository;
import com.brimap.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads the vendor's POs and what is already billed on each, then ranks them with {@link PoSuggester}. */
@Service
@RequiredArgsConstructor
public class PoSuggestionService {
    private final PurchaseOrderRepository pos;
    private final InvoiceRepository invoices;

    /** Same definition as P12's "already billed": other invoices on the PO that are not rejected or duplicates. */
    private static final Set<InvoiceStatus> NOT_BILLED = Set.of(InvoiceStatus.REJECTED, InvoiceStatus.DUPLICATE);

    public List<PoSuggester.Suggestion> suggest(Invoice inv) {
        if (inv.getVendorId() == null) return List.of();
        List<PurchaseOrder> vendorPos = pos.findByVendorId(inv.getVendorId());
        if (vendorPos.isEmpty()) return List.of();
        Set<String> ids = new java.util.HashSet<>();
        vendorPos.forEach(p -> ids.add(p.getId()));
        Map<String, BigDecimal> billed = new HashMap<>();
        for (Invoice o : invoices.findByVendorId(inv.getVendorId())) {
            if (o.getId().equals(inv.getId()) || o.getPoId() == null || !ids.contains(o.getPoId())) continue;
            if (NOT_BILLED.contains(o.getStatus()) || o.getSubtotal() == null) continue;
            billed.merge(o.getPoId(), o.getSubtotal(), BigDecimal::add);
        }
        return PoSuggester.rank(inv, vendorPos, billed);
    }
}
