package com.projectardor.agent.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.agent.domain.AgentMemory;

public interface AgentMemoryRepository extends JpaRepository<AgentMemory, UUID> {
    Optional<AgentMemory> findByUserId(UUID userId);
}
