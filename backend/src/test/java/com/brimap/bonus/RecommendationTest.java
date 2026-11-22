package com.brimap.bonus;

import com.brimap.model.Flag;
import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationTest {
    static Invoice inv() {
        Invoice i = new Invoice();
        i.setId("AP-1"); i.setStatus(InvoiceStatus.PENDING_APPROVAL); i.setContractId("C012");
        return i;
    }

    static Flag flag(String code, Severity s, String status) {
        Flag f = new Flag();
        f.setRuleCode(code); f.setSeverity(s); f.setStatus(status); f.setTitle(code); f.setMessage(code + " message");
        return f;
    }

    @Test
    void cleanRecurringBill_isApprove() {
        var r = Recommendation.of(inv(), List.of(), 10, true);
        assertThat(r.verdict()).isEqualTo("APPROVE");
        assertThat(r.headline()).isEqualTo("Approve: within contract C012, vendor paid 10 times before, no open flags");
    }

    @Test
    void acknowledgedSplitWarning_isLookCloser() {
        var r = Recommendation.of(inv(), List.of(flag("S01", Severity.WARN, "ACKNOWLEDGED")), 4, true);
        assertThat(r.verdict()).isEqualTo("LOOK_CLOSER");
        assertThat(r.headline()).startsWith("Look closer: S01 acknowledged by the clerk");
    }

    @Test
    void openBlock_isHold() {
        var r = Recommendation.of(inv(), List.of(flag("B02", Severity.BLOCK, "OPEN")), 12, true);
        assertThat(r.verdict()).isEqualTo("HOLD");
        assertThat(r.headline()).isEqualTo("Do not approve yet: B02 B02");
    }
}
