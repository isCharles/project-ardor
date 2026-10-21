package com.projectardor.assistant.memory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.projectardor.assistant.memory.domain.AgentMemoryItem;

public interface AgentMemoryItemRepository extends JpaRepository<AgentMemoryItem, UUID> {
    /** Serialize edits even when the user has no memory rows yet. Held to transaction end. */
    @org.springframework.data.jpa.repository.Query(value = "SELECT id FROM users WHERE id=:userId FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockOwner(@org.springframework.data.repository.query.Param("userId") UUID userId);
    List<AgentMemoryItem> findAllByUserIdOrderByCreatedAtAsc(UUID userId);
    Optional<AgentMemoryItem> findByIdAndUserId(UUID id, UUID userId);
    boolean existsByUserIdAndContentIgnoreCase(UUID userId, String content);
    void deleteAllByUserId(UUID userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            UPDATE user_agent_memory_items
            SET embedding = NULL, embedding_dim = NULL, embedding_model = NULL, embedded_at = NULL
            WHERE user_id = :userId AND id = :id
            """, nativeQuery = true)
    void invalidateEmbedding(@org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("id") UUID id);
}
