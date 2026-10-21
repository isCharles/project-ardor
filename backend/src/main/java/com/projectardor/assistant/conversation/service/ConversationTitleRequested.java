package com.projectardor.assistant.conversation.service;

import java.util.UUID;

public record ConversationTitleRequested(UUID userId, UUID conversationId, UUID assistantMessageId) {}
