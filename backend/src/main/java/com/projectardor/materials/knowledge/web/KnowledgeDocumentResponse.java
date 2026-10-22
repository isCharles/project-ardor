package com.projectardor.materials.knowledge.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.materials.knowledge.domain.KnowledgeDocument;
import com.projectardor.materials.knowledge.domain.KnowledgeSourceType;
import com.projectardor.materials.knowledge.service.KnowledgeDocumentView;

public record KnowledgeDocumentResponse(
        UUID id,
        String title,
        KnowledgeSourceType sourceType,
        String sourceUrl,
        String originalFilename,
        long chunkCount,
        Instant createdAt) {

    public static KnowledgeDocumentResponse from(KnowledgeDocument document, long chunkCount) {
        return fromView(KnowledgeDocumentView.from(document, chunkCount));
    }

    public static KnowledgeDocumentResponse fromView(KnowledgeDocumentView document) {
        return new KnowledgeDocumentResponse(
                document.id(), document.title(), document.sourceType(), document.sourceUrl(),
                document.originalFilename(), document.chunkCount(), document.createdAt());
    }
}
