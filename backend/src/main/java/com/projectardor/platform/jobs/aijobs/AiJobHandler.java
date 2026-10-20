package com.projectardor.platform.jobs.aijobs;

import java.util.UUID;

/** A domain owns validation, execution and its compatible result link. */
public interface AiJobHandler {
    AiJobType type();
    default void validateEnqueue(UUID userId, UUID resourceId, String payload, boolean existingRequest) {}
    String perform(AiJobWork work);
}
