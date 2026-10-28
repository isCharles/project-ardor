package com.projectardor.assistant.memory.web;

import jakarta.validation.constraints.NotNull;

/** Pinning is always an explicit user choice; the server never promotes memories on its own. */
public record AgentMemoryPinRequest(@NotNull Boolean pinned) {
}
