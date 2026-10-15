package com.projectardor.agent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.projectardor.agent.domain.AgentMemoryItem;

public interface AgentMemoryItemRepository extends JpaRepository<AgentMemoryItem, UUID> {
    /** Serialize edits even when the user has no memory rows yet. Held to transaction end. */
    @org.springframework.data.jpa.repository.Query(value = "SELECT id FROM users WHERE id=:userId FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockOwner(@org.springframework.data.repository.query.Param("userId") UUID userId);
    List<AgentMemoryItem> findAllByUserIdOrderByCreatedAtAsc(UUID userId);
    Optional<AgentMemoryItem> findByIdAndUserId(UUID id, UUID userId);
    boolean existsByUserIdAndContentIgnoreCase(UUID userId, String content);
    void deleteAllByUserId(UUID userId);
}
