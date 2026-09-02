package com.projectardor.agent.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "user_agent_memory_items")
public class AgentMemoryItem {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 4000) private String content;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected AgentMemoryItem() {}
    private AgentMemoryItem(UUID userId, String content) { this.id = UUID.randomUUID(); this.userId = userId; this.content = content; }
    public static AgentMemoryItem create(UUID userId, String content) { return new AgentMemoryItem(userId, content); }
    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
