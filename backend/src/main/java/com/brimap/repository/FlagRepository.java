package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FlagRepository extends JpaRepository<Flag, Long> {
    List<Flag> findByInvoiceId(String invoiceId);
    List<Flag> findByInvoiceIdAndStatus(String invoiceId, String status);
}
