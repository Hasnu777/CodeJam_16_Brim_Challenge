package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VendorBankAccountRepository extends JpaRepository<VendorBankAccount, Long> {
    List<VendorBankAccount> findByVendorIdOrderByVersionDesc(String vendorId);
    Optional<VendorBankAccount> findFirstByVendorIdAndStatus(String vendorId, String status);
    List<VendorBankAccount> findByAccountNumber(String accountNumber);
}
