package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, Long> {
    List<ApprovalStep> findByInvoiceIdOrderBySeq(String invoiceId);
    List<ApprovalStep> findByDecisionAndHistoricalFalse(String decision);
    List<ApprovalStep> findByInvoiceIdAndHistoricalFalseOrderBySeq(String invoiceId);
}
