package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApprovalPolicyRepository extends JpaRepository<ApprovalPolicy, String> {
    List<ApprovalPolicy> findAllByOrderByPriorityAsc();
}
