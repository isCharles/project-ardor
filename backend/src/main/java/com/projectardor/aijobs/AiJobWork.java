package com.projectardor.aijobs;

import java.util.UUID;

/** Trusted persisted execution identity, separate from transport payload and storage bookkeeping. */
public record AiJobWork(UUID id, UUID userId, AiJobType type, UUID resourceId, String payload) {}
