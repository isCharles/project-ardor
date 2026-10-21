package com.projectardor.learning.plan.service;

import java.util.Set;
import java.util.UUID;
import com.projectardor.learning.plan.domain.LearningOriginKind;

/** Source-domain adapter: authorize an actual source, returning identity only, never its private body. */
public interface LearningOriginHandler {
    Set<LearningOriginKind> kinds();
    Reference resolve(UUID owner, LearningOriginKind kind, UUID resourceId);
    record Reference(UUID resourceId, UUID recordId) {}
}
