package com.projectardor.agent.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "messages")
public class ConversationMessage {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private String runTrace;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversationMessage() {
    }

    private ConversationMessage(UUID userId, UUID conversationId, String role, String content) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
    }

    public static ConversationMessage user(UUID userId, UUID conversationId, String content) {
        return new ConversationMessage(userId, conversationId, "USER", content);
    }

    public static ConversationMessage assistant(UUID userId, UUID conversationId, String content) {
        return new ConversationMessage(userId, conversationId, "ASSISTANT", content);
    }

    public static ConversationMessage assistant(UUID userId, UUID conversationId, String content, String runTrace) {
        ConversationMessage message = assistant(userId, conversationId, content);
        message.runTrace = runTrace;
        return message;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getConversationId() { return conversationId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getRunTrace() { return runTrace; }
    public Instant getCreatedAt() { return createdAt; }
}
