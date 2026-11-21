package com.brimap.workflow;

import com.brimap.model.InvoiceStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.brimap.model.InvoiceStatus.*;

/**
 * Allowed invoice transitions. RECEIVED -> EXTRACTED -> IN_REVIEW -> PENDING_APPROVAL -> APPROVED -> SCHEDULED
 * -> RELEASED -> PAID, with side states ON_HOLD, REJECTED, DUPLICATE.
 */
public final class InvoiceStateMachine {
    private InvoiceStateMachine() {}

    private static final Map<InvoiceStatus, Set<InvoiceStatus>> ALLOWED = new EnumMap<>(InvoiceStatus.class);

    static {
        ALLOWED.put(RECEIVED, EnumSet.of(EXTRACTED, IN_REVIEW, ON_HOLD, REJECTED));
        ALLOWED.put(EXTRACTED, EnumSet.of(IN_REVIEW, ON_HOLD, DUPLICATE, REJECTED, EXTRACTED));
        ALLOWED.put(IN_REVIEW, EnumSet.of(PENDING_APPROVAL, ON_HOLD, DUPLICATE, REJECTED, EXTRACTED));
        ALLOWED.put(PENDING_APPROVAL, EnumSet.of(APPROVED, REJECTED, ON_HOLD, IN_REVIEW));
        ALLOWED.put(APPROVED, EnumSet.of(SCHEDULED, ON_HOLD));
        // SCHEDULED -> APPROVED when a scheduled payment is cancelled
        ALLOWED.put(SCHEDULED, EnumSet.of(RELEASED, ON_HOLD, APPROVED));
        // RELEASED -> SCHEDULED when the EFT fails and is rescheduled
        ALLOWED.put(RELEASED, EnumSet.of(PAID, SCHEDULED));
        ALLOWED.put(PAID, EnumSet.noneOf(InvoiceStatus.class));
        // Lifting a hold returns to the pre-hold status (checked separately) or back to review
        ALLOWED.put(ON_HOLD, EnumSet.of(IN_REVIEW, EXTRACTED, PENDING_APPROVAL, APPROVED, SCHEDULED, REJECTED, DUPLICATE));
        // A duplicate flagged in error goes back to review; a confirmed one is rejected
        ALLOWED.put(DUPLICATE, EnumSet.of(IN_REVIEW, REJECTED));
        ALLOWED.put(REJECTED, EnumSet.of(IN_REVIEW));
    }

    public static boolean canTransition(InvoiceStatus from, InvoiceStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<InvoiceStatus> next(InvoiceStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    /** Statuses in which a clerk may still edit fields and coding. */
    public static boolean editable(InvoiceStatus s) {
        return s == RECEIVED || s == EXTRACTED || s == IN_REVIEW || s == ON_HOLD || s == DUPLICATE;
    }
}
