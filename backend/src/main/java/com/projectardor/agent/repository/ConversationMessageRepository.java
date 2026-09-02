package com.projectardor.agent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.agent.domain.ConversationMessage;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {
    List<ConversationMessage> findTop40ByUserIdAndConversationIdOrderByCreatedAtDesc(
            UUID userId, UUID conversationId);

    List<ConversationMessage> findByUserIdAndConversationIdOrderByCreatedAtAsc(
            UUID userId, UUID conversationId);
}
