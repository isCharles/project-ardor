package com.projectardor.auth.repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.auth.domain.UserAccount;
import com.projectardor.auth.domain.UserRole;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    List<UserAccount> findAllByOrderByCreatedAtDesc();

    long countByRole(UserRole role);
}
