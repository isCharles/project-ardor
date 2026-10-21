package com.projectardor.assistant.memory.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.assistant.memory.domain.AgentMemory;

public interface AgentMemoryRepository extends JpaRepository<AgentMemory, UUID> {
    Optional<AgentMemory> findByUserId(UUID userId);
}
