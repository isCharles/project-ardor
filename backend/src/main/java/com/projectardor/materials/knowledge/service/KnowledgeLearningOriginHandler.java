package com.projectardor.materials.knowledge.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.service.LearningOriginHandler;

@Component
public class KnowledgeLearningOriginHandler implements LearningOriginHandler {
    private final KnowledgeSourceAccess knowledge;
    public KnowledgeLearningOriginHandler(KnowledgeSourceAccess knowledge) { this.knowledge=knowledge; }
    public Set<LearningOriginKind> kinds() { return Set.of(LearningOriginKind.KNOWLEDGE); }
    public Reference resolve(UUID owner, LearningOriginKind kind, UUID resourceId) {
        knowledge.require(owner,resourceId);
        return new Reference(resourceId,null);
    }
}
