package com.brimap.audit;

import com.brimap.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditHasherTest {
    private static AuditEvent event(long id, String prev, String actor, String after) {
        AuditEvent e = new AuditEvent();
        e.setId(id);
        e.setTs(Instant.parse("2026-11-02T14:00:00.123456Z"));
        e.setActor(actor);
        e.setAction("APPROVED");
        e.setEntity("invoice");
        e.setEntityId("AP-00427");
        e.setAfterJson(after);
        e.setPrevHash(prev);
        e.setHash(AuditHasher.hash(e));
        return e;
    }

    @Test
    void hashIsDeterministicAndChained() {
        AuditEvent a = event(1, AuditHasher.GENESIS, "U003", "{\"status\":\"APPROVED\"}");
        AuditEvent b = event(2, a.getHash(), "U002", "{\"status\":\"SCHEDULED\"}");
        assertThat(a.getHash()).hasSize(64).isEqualTo(AuditHasher.hash(a));
        assertThat(b.getPrevHash()).isEqualTo(a.getHash());
    }

    @Test
    void anyEditChangesTheHash() {
        AuditEvent a = event(1, AuditHasher.GENESIS, "U003", "{\"status\":\"APPROVED\"}");
        String original = a.getHash();
        a.setActor("U004"); // someone rewrites who approved it
        assertThat(AuditHasher.hash(a)).isNotEqualTo(original);
    }

    @Test
    void separatorInjectionDoesNotCollide() {
        AuditEvent a = event(1, AuditHasher.GENESIS, "U003|APPROVED", null);
        AuditEvent b = event(1, AuditHasher.GENESIS, "U003", null);
        b.setAction("|APPROVED" + "APPROVED");
        assertThat(AuditHasher.hash(a)).isNotEqualTo(AuditHasher.hash(b));
    }
}
