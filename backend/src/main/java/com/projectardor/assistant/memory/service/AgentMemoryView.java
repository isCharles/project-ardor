package com.projectardor.assistant.memory.service;

import java.time.Instant;

/** Current memory projection, not a replacement for versioned item history. */
public record AgentMemoryView(String content, Instant updatedAt) {}
