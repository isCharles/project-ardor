package com.projectardor.knowledge.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocument {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 240) private String title;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 32)
    private KnowledgeSourceType sourceType;
    @Column(name = "source_url", length = 2048) private String sourceUrl;
    @Column(name = "original_filename", length = 255) private String originalFilename;
    @Column(name = "content_type", length = 160) private String contentType;
    @Column(name = "raw_content", nullable = false, columnDefinition = "text") private String rawContent;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected KnowledgeDocument() {}

    private KnowledgeDocument(
            UUID userId,
            String title,
            KnowledgeSourceType sourceType,
            String sourceUrl,
            String originalFilename,
            String contentType,
            String rawContent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.title = title;
        this.sourceType = sourceType;
        this.sourceUrl = sourceUrl;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.rawContent = rawContent;
    }

    public static KnowledgeDocument uploaded(
            UUID userId, String title, String originalFilename, String contentType, String rawContent) {
        return new KnowledgeDocument(
                userId, title, KnowledgeSourceType.USER_UPLOAD, null, originalFilename, contentType, rawContent);
    }

    public static KnowledgeDocument fromWeb(UUID userId, String title, String sourceUrl, String rawContent) {
        return new KnowledgeDocument(userId, title, KnowledgeSourceType.WEB, sourceUrl, null, "text/html", rawContent);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public KnowledgeSourceType getSourceType() { return sourceType; }
    public String getSourceUrl() { return sourceUrl; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public String getRawContent() { return rawContent; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
