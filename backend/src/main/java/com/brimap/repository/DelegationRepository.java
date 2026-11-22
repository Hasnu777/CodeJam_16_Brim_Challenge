package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {
    List<Delegation> findByFromUserId(String fromUserId);
    List<Delegation> findByToUserId(String toUserId);
}
