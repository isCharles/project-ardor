package com.projectardor.materials.knowledge.web;

import java.util.UUID;

import com.projectardor.materials.knowledge.domain.KnowledgeSourceType;
import com.projectardor.materials.knowledge.service.KnowledgeSearchHit;

public record KnowledgeSearchResult(
        UUID documentId,
        String title,
        KnowledgeSourceType sourceType,
        String sourceUrl,
        String content,
        double score) {
    public static KnowledgeSearchResult fromView(KnowledgeSearchHit hit) {
        return new KnowledgeSearchResult(hit.documentId(), hit.title(), hit.sourceType(),
                hit.sourceUrl(), hit.content(), hit.score());
    }
}
