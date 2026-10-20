package com.projectardor.agent.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.projectardor.agent.AgentContextReference;

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
    @Column(name = "context_references", nullable = false, columnDefinition = "jsonb")
    private List<AgentContextReference> contextReferences = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private String runTrace;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal_resolutions", nullable = false, columnDefinition = "jsonb")
    private java.util.Map<String, String> proposalResolutions = new java.util.HashMap<>();

    /** Durable run/request identity. Null for legacy synchronous chat and unparseable history. */
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "run_id")
    private UUID runId;

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

    public static ConversationMessage user(UUID userId, UUID conversationId, String content,
            List<AgentContextReference> contextReferences) {
        ConversationMessage message = user(userId, conversationId, content);
        message.contextReferences = contextReferences == null ? new ArrayList<>() : new ArrayList<>(contextReferences);
        return message;
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

    /** Links an already saved exchange to the durable run that produced it. */
    public void linkRun(UUID requestId, UUID runId) {
        this.requestId = requestId;
        this.runId = runId;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getConversationId() { return conversationId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public List<AgentContextReference> getContextReferences() { return List.copyOf(contextReferences); }
    public String getRunTrace() { return runTrace; }
    public java.util.Map<String, String> getProposalResolutions() { return java.util.Map.copyOf(proposalResolutions); }
    public UUID getRequestId() { return requestId; }
    public UUID getRunId() { return runId; }
    public Instant getCreatedAt() { return createdAt; }
}
