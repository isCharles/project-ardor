package com.projectardor.assistant.memory.service;

import java.util.UUID;

/** Asks the background consolidator to look for new long-term facts after an exchange. */
public record MemoryConsolidationRequested(UUID userId, UUID conversationId) {}
