package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FxRateRepository extends JpaRepository<FxRate, java.time.LocalDate> {
    Optional<FxRate> findFirstByDateLessThanEqualOrderByDateDesc(java.time.LocalDate date);
}
