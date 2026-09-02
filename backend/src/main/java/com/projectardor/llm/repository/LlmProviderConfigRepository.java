package com.projectardor.llm.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.llm.domain.LlmProviderConfig;

public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfig, UUID> {

    Optional<LlmProviderConfig> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
