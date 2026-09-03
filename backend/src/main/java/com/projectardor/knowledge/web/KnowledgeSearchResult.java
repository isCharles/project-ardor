package com.projectardor.knowledge.web;

import java.util.UUID;

import com.projectardor.knowledge.domain.KnowledgeSourceType;

public record KnowledgeSearchResult(
        UUID documentId,
        String title,
        KnowledgeSourceType sourceType,
        String sourceUrl,
        String content,
        double score) {
}
