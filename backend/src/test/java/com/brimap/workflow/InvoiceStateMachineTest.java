package com.brimap.workflow;

import org.junit.jupiter.api.Test;

import static com.brimap.model.InvoiceStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class InvoiceStateMachineTest {
    @Test
    void happyPath() {
        assertThat(InvoiceStateMachine.canTransition(RECEIVED, EXTRACTED)).isTrue();
        assertThat(InvoiceStateMachine.canTransition(EXTRACTED, IN_REVIEW)).isTrue();
        assertThat(InvoiceStateMachine.canTransition(IN_REVIEW, PENDING_APPROVAL)).isTrue();
        assertThat(InvoiceStateMachine.canTransition(PENDING_APPROVAL, APPROVED)).isTrue();
        assertThat(InvoiceStateMachine.canTransition(APPROVED, SCHEDULED)).isTrue();
        assertThat(InvoiceStateMachine.canTransition(SCHEDULED, RELEASED)).isTrue();
        assertThat(InvoiceStateMachine.canTransition(RELEASED, PAID)).isTrue();
    }

    @Test
    void cannotSkipApprovalOrRevivePaid() {
        assertThat(InvoiceStateMachine.canTransition(IN_REVIEW, APPROVED)).isFalse();
        assertThat(InvoiceStateMachine.canTransition(EXTRACTED, SCHEDULED)).isFalse();
        assertThat(InvoiceStateMachine.canTransition(PENDING_APPROVAL, RELEASED)).isFalse();
        assertThat(InvoiceStateMachine.next(PAID)).isEmpty();
    }

    @Test
    void sideStates() {
        assertThat(InvoiceStateMachine.canTransition(PENDING_APPROVAL, ON_HOLD)).isTrue();
        assertThat(InvoiceStateMachine.canTransition(ON_HOLD, PENDING_APPROVAL)).isTrue();
        assertThat(InvoiceStateMachine.canTransition(DUPLICATE, IN_REVIEW)).isTrue();
        assertThat(InvoiceStateMachine.canTransition(RELEASED, ON_HOLD)).isFalse(); // money already left
    }
}
