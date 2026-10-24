package com.projectardor.assistant.tools.modules;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import com.projectardor.learning.plan.service.LearningCreationCommand;

/** Trusted owner and existing host callbacks, captured separately for each run. */
public record AgentToolContext(UUID userId, DeletionProposal proposals,
        BiFunction<String, Object, String> untrustedContent,
        UUID actionId, LearningProposal learningProposals) {
    /** Read/delete-only adapters do not need the learning proposal capability. */
    public AgentToolContext(UUID userId, DeletionProposal proposals,
            BiFunction<String, Object, String> untrustedContent) {
        this(userId, proposals, untrustedContent, null, input -> {
            throw new IllegalStateException("Learning proposal host is not bound");
        });
    }

    public AgentToolContext {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(proposals);
        Objects.requireNonNull(untrustedContent);
        Objects.requireNonNull(learningProposals);
    }

    @FunctionalInterface
    public interface DeletionProposal {
        Map<String, Object> propose(String kind, UUID targetId, String label, String detail, String endpoint);
    }

    @FunctionalInterface
    public interface LearningProposal {
        Map<String, Object> propose(LearningCreationCommand input);
    }
}
