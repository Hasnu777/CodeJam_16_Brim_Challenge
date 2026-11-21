package com.brimap.audit;

import com.brimap.auth.Auth;
import com.brimap.model.AuditEvent;
import com.brimap.repository.AuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only writer of audit_events. Rows are chained: each row stores the previous row's hash.
 * A transaction-scoped Postgres advisory lock serializes writers so the chain stays linear.
 */
@Service
@RequiredArgsConstructor
public class AuditService {
    private static final long CHAIN_LOCK = 0x4155444954L; // "AUDIT"

    private final AuditEventRepository repo;
    private final EntityManager em;
    private final ObjectMapper json;

    /** Record an event inside the caller's transaction (rolls back with it). */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent record(String action, String entity, String entityId, Object before, Object after, String detail) {
        return write(Auth.actorId(), null, action, entity, entityId, before, after, detail);
    }

    /** Same, with an explicit on-behalf-of user (delegate actions). */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent recordOnBehalf(String onBehalfOf, String action, String entity, String entityId,
                                     Object before, Object after, String detail) {
        return write(Auth.actorId(), onBehalfOf, action, entity, entityId, before, after, detail);
    }

    /** Denials are recorded in their own transaction so they survive the rollback of the denied request. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenial(String actor, String method, String path, String reason) {
        write(actor == null ? "anonymous" : actor, null, "ACCESS_DENIED", "request", method + " " + path, null, null, reason);
    }

    /** For actions with no surrounding transaction (e.g. login). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStandalone(String actor, String action, String entity, String entityId, String detail) {
        write(actor, null, action, entity, entityId, null, null, detail);
    }

    private AuditEvent write(String actor, String onBehalfOf, String action, String entity, String entityId,
                             Object before, Object after, String detail) {
        em.createNativeQuery("select pg_advisory_xact_lock(" + CHAIN_LOCK + ")").getSingleResult();
        AuditEvent last = repo.findTopByOrderByIdDesc().orElse(null);
        AuditEvent e = new AuditEvent();
        e.setId(last == null ? 1L : last.getId() + 1);
        e.setTs(Instant.now().truncatedTo(ChronoUnit.MICROS)); // DB keeps microseconds; hash what we store
        e.setActor(actor);
        e.setOnBehalfOf(onBehalfOf);
        e.setAction(action);
        e.setEntity(entity);
        e.setEntityId(entityId);
        e.setBeforeJson(toJson(before));
        e.setAfterJson(toJson(after));
        e.setDetail(detail);
        e.setPrevHash(last == null ? AuditHasher.GENESIS : last.getHash());
        e.setHash(AuditHasher.hash(e));
        return repo.save(e);
    }

    private String toJson(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            return String.valueOf(o);
        }
    }

    /** Re-compute the whole chain and report every break. */
    @Transactional(readOnly = true)
    public Map<String, Object> verify() {
        List<AuditEvent> all = repo.findAllByOrderByIdAsc();
        List<Map<String, Object>> breaks = new ArrayList<>();
        String prev = AuditHasher.GENESIS;
        long expectedId = 1;
        for (AuditEvent e : all) {
            String recomputed = AuditHasher.hash(e);
            List<String> problems = new ArrayList<>();
            if (e.getId() != expectedId) problems.add("gap: expected id " + expectedId);
            if (!prev.equals(e.getPrevHash())) problems.add("prev_hash does not match previous row");
            if (!recomputed.equals(e.getHash())) problems.add("row content does not match its hash");
            if (!problems.isEmpty()) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("id", e.getId());
                b.put("problems", problems);
                breaks.add(b);
            }
            prev = e.getHash();
            expectedId = e.getId() + 1;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", breaks.isEmpty());
        out.put("events", all.size());
        out.put("headHash", all.isEmpty() ? null : all.get(all.size() - 1).getHash());
        out.put("breaks", breaks);
        return out;
    }
}
