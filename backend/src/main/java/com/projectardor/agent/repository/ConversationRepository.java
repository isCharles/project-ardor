package com.projectardor.agent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.agent.domain.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    Optional<Conversation> findByIdAndUserId(UUID id, UUID userId);
    List<Conversation> findAllByUserIdAndStatusOrderByPinnedDescUpdatedAtDesc(UUID userId, String status);
}
