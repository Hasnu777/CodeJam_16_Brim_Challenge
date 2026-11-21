package com.brimap.audit;

import com.brimap.model.AuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADM','AUD')")
public class AuditController {
    private final EntityManager em;
    private final AuditService audit;

    @GetMapping
    @Transactional(readOnly = true)
    public List<AuditEvent> list(@RequestParam(required = false) String entity, @RequestParam(required = false) String entityId,
                                 @RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                                 @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                 @RequestParam(defaultValue = "500") int limit) {
        return query(entity, entityId, actor, action, from, to, Math.min(limit, 5000));
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<String> export(@RequestParam(required = false) String entity, @RequestParam(required = false) String entityId,
                                         @RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                                         @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        StringBuilder sb = new StringBuilder("id,ts,actor,on_behalf_of,action,entity,entity_id,before,after,detail,prev_hash,hash\n");
        for (AuditEvent e : query(entity, entityId, actor, action, from, to, 1_000_000)) {
            sb.append(e.getId()).append(',').append(e.getTs()).append(',').append(c(e.getActor())).append(',')
                    .append(c(e.getOnBehalfOf())).append(',').append(c(e.getAction())).append(',').append(c(e.getEntity())).append(',')
                    .append(c(e.getEntityId())).append(',').append(c(e.getBeforeJson())).append(',').append(c(e.getAfterJson())).append(',')
                    .append(c(e.getDetail())).append(',').append(e.getPrevHash()).append(',').append(e.getHash()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit-log.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(sb.toString());
    }

    @GetMapping("/verify")
    public Map<String, Object> verify() { return audit.verify(); }

    private List<AuditEvent> query(String entity, String entityId, String actor, String action, LocalDate from, LocalDate to, int limit) {
        StringBuilder q = new StringBuilder("select e from AuditEvent e where 1=1");
        Map<String, Object> p = new HashMap<>();
        if (entity != null) { q.append(" and e.entity = :entity"); p.put("entity", entity); }
        if (entityId != null) { q.append(" and e.entityId = :entityId"); p.put("entityId", entityId); }
        if (actor != null) { q.append(" and (e.actor = :actor or e.onBehalfOf = :actor)"); p.put("actor", actor); }
        if (action != null) { q.append(" and e.action = :action"); p.put("action", action); }
        if (from != null) { q.append(" and e.ts >= :from"); p.put("from", from.atStartOfDay().toInstant(ZoneOffset.UTC)); }
        if (to != null) { q.append(" and e.ts < :to"); p.put("to", to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)); }
        q.append(" order by e.id desc");
        TypedQuery<AuditEvent> tq = em.createQuery(q.toString(), AuditEvent.class).setMaxResults(limit);
        p.forEach(tq::setParameter);
        return tq.getResultList();
    }

    private static String c(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
