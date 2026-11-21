package com.brimap.intake;

import com.brimap.model.Invoice;

/**
 * A deterministic step run on a freshly extracted (or re-extracted / edited) invoice, inside the pipeline's
 * transaction: GL coding suggestions, validation rules, duplicate checks. Ordered by {@link #order()}.
 */
public interface InvoiceProcessingStep {
    int order();

    void process(Invoice invoice);
}
