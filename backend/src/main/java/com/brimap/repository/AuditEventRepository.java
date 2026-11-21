package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findAllByOrderByIdAsc();
    Optional<AuditEvent> findTopByOrderByIdDesc();
    List<AuditEvent> findByEntityAndEntityIdOrderByIdAsc(String entity, String entityId);
}
