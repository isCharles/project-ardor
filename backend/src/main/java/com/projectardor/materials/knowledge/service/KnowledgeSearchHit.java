package com.projectardor.materials.knowledge.service;

import java.util.UUID;

import com.projectardor.materials.knowledge.domain.KnowledgeSourceType;

/** Ranked, user-scoped retrieval result; content remains untrusted source material. */
public record KnowledgeSearchHit(UUID documentId, String title, KnowledgeSourceType sourceType,
        String sourceUrl, String content, double score) {
}
