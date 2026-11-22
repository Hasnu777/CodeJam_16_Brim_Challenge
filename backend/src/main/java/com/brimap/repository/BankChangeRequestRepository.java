package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BankChangeRequestRepository extends JpaRepository<BankChangeRequest, String> {
    List<BankChangeRequest> findByVendorIdOrderByChangedAtDesc(String vendorId);
    List<BankChangeRequest> findByStatus(String status);
}
