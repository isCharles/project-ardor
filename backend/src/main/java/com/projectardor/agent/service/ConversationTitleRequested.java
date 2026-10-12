package com.projectardor.agent.service;

import java.util.UUID;

public record ConversationTitleRequested(UUID userId, UUID conversationId, UUID assistantMessageId) {}
