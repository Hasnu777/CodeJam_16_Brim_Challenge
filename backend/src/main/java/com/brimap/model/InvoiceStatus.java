package com.brimap.model;

/** Invoice lifecycle. Transitions are enforced by InvoiceWorkflowService only. */
public enum InvoiceStatus {
    RECEIVED, EXTRACTED, IN_REVIEW, PENDING_APPROVAL, APPROVED, SCHEDULED, RELEASED, PAID,
    ON_HOLD, REJECTED, DUPLICATE
}
