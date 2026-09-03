package com.projectardor.knowledge.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.knowledge.domain.KnowledgeChunk;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, UUID> {
    List<KnowledgeChunk> findAllByUserId(UUID userId);
    long countByDocumentIdAndUserId(UUID documentId, UUID userId);
}
