package com.projectardor.agent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.agent.domain.ConversationMessage;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {
    java.util.Optional<ConversationMessage> findByIdAndUserId(UUID id, UUID userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            UPDATE messages SET proposal_resolutions = proposal_resolutions || jsonb_build_object(CAST(:key AS text), CAST(:resolution AS text))
            WHERE id = :id AND user_id = :userId AND NOT jsonb_exists(proposal_resolutions, CAST(:key AS text))
            """, nativeQuery = true)
    int resolveProposal(UUID userId, UUID id, String key, String resolution);
    List<ConversationMessage> findTop40ByUserIdAndConversationIdOrderByCreatedAtDesc(
            UUID userId, UUID conversationId);

    List<ConversationMessage> findByUserIdAndConversationIdOrderByCreatedAtAsc(
            UUID userId, UUID conversationId);

    long countByUserIdAndConversationId(UUID userId, UUID conversationId);
}
