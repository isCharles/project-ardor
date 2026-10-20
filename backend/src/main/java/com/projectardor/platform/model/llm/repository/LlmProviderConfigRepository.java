package com.projectardor.platform.model.llm.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.platform.model.llm.domain.LlmProviderConfig;

public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfig, UUID> {

    Optional<LlmProviderConfig> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
