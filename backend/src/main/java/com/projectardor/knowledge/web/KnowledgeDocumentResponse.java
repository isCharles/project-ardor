package com.projectardor.knowledge.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.knowledge.domain.KnowledgeDocument;
import com.projectardor.knowledge.domain.KnowledgeSourceType;

public record KnowledgeDocumentResponse(
        UUID id,
        String title,
        KnowledgeSourceType sourceType,
        String sourceUrl,
        String originalFilename,
        long chunkCount,
        Instant createdAt) {

    public static KnowledgeDocumentResponse from(KnowledgeDocument document, long chunkCount) {
        return new KnowledgeDocumentResponse(
                document.getId(), document.getTitle(), document.getSourceType(), document.getSourceUrl(),
                document.getOriginalFilename(), chunkCount, document.getCreatedAt());
    }
}
