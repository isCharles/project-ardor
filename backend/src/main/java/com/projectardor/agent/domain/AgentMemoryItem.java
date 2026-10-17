package com.projectardor.agent.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

/**
 * One long-term memory fact. A fact may summarize the user's own words; provenance and
 * validity intervals replace the old verbatim-quote rule.
 */
@Entity
@Table(name = "user_agent_memory_items")
public class AgentMemoryItem {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 4000) private String content;
    @Column(nullable = false, length = 24) private String kind = "fact";
    @Column(nullable = false) private float confidence = 1.0f;
    @Column(nullable = false) private short importance = 2;
    @Column(name = "source_kind", nullable = false, length = 24) private String sourceKind = "USER_EXPLICIT";
    @Column(name = "source_run_id") private UUID sourceRunId;
    @Column(name = "valid_from", nullable = false) private Instant validFrom;
    @Column(name = "valid_until") private Instant validUntil;
    @Column(name = "superseded_by") private UUID supersededBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected AgentMemoryItem() {}

    private AgentMemoryItem(UUID userId, String content) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.content = content;
    }

    public static AgentMemoryItem create(UUID userId, String content) {
        return new AgentMemoryItem(userId, content);
    }

    public static AgentMemoryItem create(UUID userId, String content, String kind, String sourceKind, UUID sourceRunId) {
        AgentMemoryItem item = new AgentMemoryItem(userId, content);
        item.kind = validKind(kind);
        item.sourceKind = validSource(sourceKind);
        item.sourceRunId = sourceRunId;
        return item;
    }

    private static String validKind(String kind) {
        return kind != null && java.util.Set.of("profile", "preference", "goal", "constraint", "state", "fact").contains(kind)
                ? kind : "fact";
    }

    private static String validSource(String sourceKind) {
        return sourceKind != null && java.util.Set.of("USER_EXPLICIT", "USER_STATED", "INFERRED").contains(sourceKind)
                ? sourceKind : "USER_EXPLICIT";
    }

    @PrePersist void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (validFrom == null) validFrom = now;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public boolean isValid() { return validUntil == null; }

    public void revise(String content) { this.content = content; }

    /** Marks this fact as no longer true rather than deleting it. */
    public void invalidate(Instant when, UUID replacementId) {
        this.validUntil = when == null ? Instant.now() : when;
        this.supersededBy = replacementId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getContent() { return content; }
    public String getKind() { return kind; }
    public float getConfidence() { return confidence; }
    public short getImportance() { return importance; }
    public String getSourceKind() { return sourceKind; }
    public UUID getSourceRunId() { return sourceRunId; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public UUID getSupersededBy() { return supersededBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
