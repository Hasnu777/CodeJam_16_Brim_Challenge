package com.brimap.validation;

import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.repository.InvoiceRepository;
import com.brimap.validation.rules.S01SplitInvoices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Cross-invoice rules (duplicates within the batch, splits, monthly caps) depend on which other bills exist, and
 * the inbox is processed in parallel. So after a batch, and after any single upload, the affected open bills are
 * validated again. Each invoice is validated in its own transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RevalidationService {
    private final InvoiceRepository invoices;
    private final ValidationService validation;

    /** All bills not yet approved (plus ones parked as duplicates). Returns how many were validated. */
    public int revalidateOpen() {
        List<Invoice> open = invoices.findByHistoricalFalse().stream().filter(RevalidationService::reviewable).toList();
        open.forEach(this::safeValidate);
        return open.size();
    }

    /** Open bills from the same vendor as {@code changed} (they may now be duplicates of it or split with it). */
    public void revalidateVendor(Invoice changed) {
        if (changed.getVendorId() == null) return;
        invoices.findByVendorId(changed.getVendorId()).stream()
                .filter(i -> !i.isHistorical() && !Objects.equals(i.getId(), changed.getId()) && reviewable(i))
                .forEach(this::safeValidate);
        // Approved / scheduled bills: only their payment holds follow the vendor (D-088).
        invoices.findByVendorId(changed.getVendorId()).stream()
                .filter(i -> !i.isHistorical() && (i.getStatus() == InvoiceStatus.APPROVED || i.getStatus() == InvoiceStatus.SCHEDULED))
                .forEach(i -> {
                    try {
                        validation.clearStaleHolds(i);
                    } catch (RuntimeException e) {
                        log.error("Clearing payment holds on {} failed", i.getId(), e);
                    }
                });
    }

    private void safeValidate(Invoice i) {
        try {
            validation.validate(i);
        } catch (RuntimeException e) {
            log.error("Re-validation of {} failed", i.getId(), e);
        }
    }

    private static boolean reviewable(Invoice i) {
        return S01SplitInvoices.AWAITING_APPROVAL.contains(i.getStatus()) || i.getStatus() == InvoiceStatus.DUPLICATE;
    }
}
