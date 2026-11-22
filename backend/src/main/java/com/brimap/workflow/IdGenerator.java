package com.brimap.workflow;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** New ids continue the dataset's sequences: AP-00427, PAY-00416, V057, BC-010, EFT-0001. */
@Component
@RequiredArgsConstructor
public class IdGenerator {
    private final EntityManager em;

    private long next(String seq) {
        // Bound parameter, not string concatenation: safe even if a sequence name ever came from outside.
        return ((Number) em.createNativeQuery("select nextval(cast(?1 as regclass))").setParameter(1, seq).getSingleResult()).longValue();
    }

    public String invoiceId() { return String.format("AP-%05d", next("invoice_number_seq")); }

    public String paymentId() { return String.format("PAY-%05d", next("payment_number_seq")); }

    public String vendorId() { return String.format("V%03d", next("vendor_number_seq")); }

    public String bankChangeId() { return String.format("BC-%03d", next("bank_change_number_seq")); }

    public String batchId() { return String.format("EFT-%04d", next("batch_number_seq")); }
}
