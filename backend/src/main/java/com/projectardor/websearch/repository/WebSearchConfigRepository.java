package com.projectardor.websearch.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.websearch.domain.WebSearchConfig;

public interface WebSearchConfigRepository extends JpaRepository<WebSearchConfig, UUID> {
    Optional<WebSearchConfig> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
