package com.projectardor.platform.identity.auth.repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.platform.identity.auth.domain.UserAccount;
import com.projectardor.platform.identity.auth.domain.UserRole;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    List<UserAccount> findAllByOrderByCreatedAtDesc();

    long countByRole(UserRole role);
}
