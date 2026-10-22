package com.projectardor.materials.knowledge.web;

import java.util.List;
import com.projectardor.materials.knowledge.service.KnowledgeResearchView;

public record KnowledgeResearchResponse(String query, List<KnowledgeDocumentResponse> imported) {
    public static KnowledgeResearchResponse fromView(KnowledgeResearchView view) {
        return new KnowledgeResearchResponse(view.query(),
                view.imported().stream().map(KnowledgeDocumentResponse::fromView).toList());
    }
}
