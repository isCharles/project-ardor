package com.projectardor.materials.knowledge.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.materials.knowledge.domain.KnowledgeDocument;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {
    Optional<KnowledgeDocument> findByIdAndUserId(UUID id, UUID userId);
    Optional<KnowledgeDocument> findByUserIdAndSourceUrl(UUID userId, String sourceUrl);
    List<KnowledgeDocument> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}
