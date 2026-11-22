package com.brimap.repository;

import com.brimap.model.VirtualCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VirtualCardRepository extends JpaRepository<VirtualCard, String> {
    Optional<VirtualCard> findByPaymentId(String paymentId);
}
