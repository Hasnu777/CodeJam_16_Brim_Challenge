package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, String> {
    List<Invoice> findByVendorId(String vendorId);
    List<Invoice> findByStatus(InvoiceStatus status);
    List<Invoice> findByHistoricalFalse();
    List<Invoice> findByDocumentId(Long documentId);
}
