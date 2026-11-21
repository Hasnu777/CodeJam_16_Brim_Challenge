package com.brimap.repository;

import com.brimap.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, String> {
    List<AppUser> findByRole(Role role);
}
