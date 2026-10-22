package com.projectardor.materials.knowledge.service;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.materials.knowledge.domain.KnowledgeDocument;
import com.projectardor.materials.knowledge.domain.KnowledgeSourceType;

/** Owned application snapshot shared by HTTP and assistant callers. */
public record KnowledgeDocumentView(UUID id, String title, KnowledgeSourceType sourceType,
        String sourceUrl, String originalFilename, long chunkCount, Instant createdAt) {
    public static KnowledgeDocumentView from(KnowledgeDocument document, long chunkCount) {
        return new KnowledgeDocumentView(document.getId(), document.getTitle(), document.getSourceType(),
                document.getSourceUrl(), document.getOriginalFilename(), chunkCount, document.getCreatedAt());
    }
}
