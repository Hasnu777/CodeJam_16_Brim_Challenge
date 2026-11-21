package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StoredDocumentRepository extends JpaRepository<StoredDocument, Long> {
    Optional<StoredDocument> findFirstBySha256(String sha256);
}
