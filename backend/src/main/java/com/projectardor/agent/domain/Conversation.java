package com.projectardor.agent.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(length = 240)
    private String title;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private boolean pinned;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
    }

    private Conversation(UUID userId, String title) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.title = title;
        this.status = "ACTIVE";
        this.pinned = false;
    }

    public static Conversation create(UUID userId, String title) {
        return new Conversation(userId, title);
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.status = "ARCHIVED";
        this.pinned = false;
        touch();
    }

    public void restore() {
        this.status = "ACTIVE";
        touch();
    }

    public void rename(String title) {
        this.title = title;
        touch();
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
        touch();
    }

    public void titleFromFirstMessage(String message) {
        if (title == null || title.isBlank() || "新对话".equals(title)) {
            String normalized = message == null ? "" : message.strip().replaceAll("\\s+", " ");
            String firstThought = normalized.split("[。！？!?；;\\n]", 2)[0].strip();
            String candidate = firstThought.isBlank() ? normalized : firstThought;
            int[] codePoints = candidate.codePoints().toArray();
            int limit = Math.min(codePoints.length, 24);
            this.title = new String(codePoints, 0, limit) + (codePoints.length > limit ? "…" : "");
            touch();
        }
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public boolean isPinned() { return pinned; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
