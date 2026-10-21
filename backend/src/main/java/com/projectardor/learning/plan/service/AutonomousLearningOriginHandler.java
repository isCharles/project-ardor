package com.projectardor.learning.plan.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.learning.plan.domain.LearningOriginKind;

@Component
public class AutonomousLearningOriginHandler implements LearningOriginHandler {
    public Set<LearningOriginKind> kinds() { return Set.of(LearningOriginKind.MANUAL,LearningOriginKind.AGENT); }
    public Reference resolve(UUID owner, LearningOriginKind kind, UUID resourceId) {
        // Old MANUAL/AGENT sourceId remains only in its compatibility field; it is not a real resource reference.
        return new Reference(null,null);
    }
}
