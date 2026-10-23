package com.projectardor.assistant.tools.modules;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

/** Trusted owner and existing host callbacks, captured separately for each run. */
public record AgentToolContext(UUID userId, DeletionProposal proposals,
        BiFunction<String, Object, String> untrustedContent) {
    public AgentToolContext {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(proposals);
        Objects.requireNonNull(untrustedContent);
    }

    @FunctionalInterface
    public interface DeletionProposal {
        Map<String, Object> propose(String kind, UUID targetId, String label, String detail, String endpoint);
    }
}
