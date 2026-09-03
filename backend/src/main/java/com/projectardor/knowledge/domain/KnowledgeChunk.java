package com.projectardor.knowledge.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_chunks")
public class KnowledgeChunk {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "document_id", nullable = false) private UUID documentId;
    @Column(name = "sequence_number", nullable = false) private int sequenceNumber;
    @Column(nullable = false, columnDefinition = "text") private String content;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected KnowledgeChunk() {}

    private KnowledgeChunk(UUID userId, UUID documentId, int sequenceNumber, String content) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.documentId = documentId;
        this.sequenceNumber = sequenceNumber;
        this.content = content;
    }

    public static KnowledgeChunk create(UUID userId, UUID documentId, int sequenceNumber, String content) {
        return new KnowledgeChunk(userId, documentId, sequenceNumber, content);
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getDocumentId() { return documentId; }
    public int getSequenceNumber() { return sequenceNumber; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
