package com.projectardor.resume.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "resumes")
public class Resume {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "parsed_text", columnDefinition = "text")
    private String parsedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 32)
    private ResumeParseStatus parseStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Resume() {
    }

    private Resume(
            UUID id,
            UUID userId,
            String originalFilename,
            String contentType,
            String storageKey,
            long sizeBytes,
            String checksumSha256) {
        this.id = id;
        this.userId = userId;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.parseStatus = ResumeParseStatus.PENDING;
    }

    public static Resume create(
            UUID id,
            UUID userId,
            String originalFilename,
            String contentType,
            String storageKey,
            long sizeBytes,
            String checksumSha256) {
        return new Resume(id, userId, originalFilename, contentType, storageKey, sizeBytes, checksumSha256);
    }

    public void markParsed(String parsedText) {
        this.parsedText = parsedText;
        this.parseStatus = ResumeParseStatus.PARSED;
    }

    public void markFailed() {
        this.parsedText = null;
        this.parseStatus = ResumeParseStatus.FAILED;
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
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public String getStorageKey() { return storageKey; }
    public long getSizeBytes() { return sizeBytes; }
    public String getChecksumSha256() { return checksumSha256; }
    public String getParsedText() { return parsedText; }
    public ResumeParseStatus getParseStatus() { return parseStatus; }
    public Instant getCreatedAt() { return createdAt; }
}
