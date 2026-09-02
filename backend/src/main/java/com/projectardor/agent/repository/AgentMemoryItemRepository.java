package com.projectardor.agent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.projectardor.agent.domain.AgentMemoryItem;

public interface AgentMemoryItemRepository extends JpaRepository<AgentMemoryItem, UUID> {
    List<AgentMemoryItem> findAllByUserIdOrderByCreatedAtAsc(UUID userId);
    Optional<AgentMemoryItem> findByIdAndUserId(UUID id, UUID userId);
    boolean existsByUserIdAndContentIgnoreCase(UUID userId, String content);
    void deleteAllByUserId(UUID userId);
}
