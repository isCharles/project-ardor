package com.projectardor.materials.knowledge.service;

import java.util.List;

/** Committed imported-document snapshots; retrieval never exposes persistence entities. */
public record KnowledgeResearchView(String query, List<KnowledgeDocumentView> imported) {
    public KnowledgeResearchView {
        imported = List.copyOf(imported);
    }
}
