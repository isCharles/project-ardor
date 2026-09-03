package com.projectardor.knowledge.web;

import java.util.List;

public record KnowledgeResearchResponse(String query, List<KnowledgeDocumentResponse> imported) {
}
