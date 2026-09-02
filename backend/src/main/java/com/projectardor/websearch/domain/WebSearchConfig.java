package com.projectardor.websearch.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "web_search_configs")
public class WebSearchConfig {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "encrypted_api_key", nullable = false)
    private byte[] encryptedApiKey;

    @Column(name = "api_key_iv", nullable = false)
    private byte[] apiKeyIv;

    @Column(name = "key_hint", nullable = false, length = 16)
    private String keyHint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebSearchConfig() {
    }

    private WebSearchConfig(UUID userId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
    }

    public static WebSearchConfig create(UUID userId) {
        return new WebSearchConfig(userId);
    }

    public void update(byte[] encryptedApiKey, byte[] apiKeyIv, String keyHint) {
        this.encryptedApiKey = Arrays.copyOf(encryptedApiKey, encryptedApiKey.length);
        this.apiKeyIv = Arrays.copyOf(apiKeyIv, apiKeyIv.length);
        this.keyHint = keyHint;
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

    public UUID getUserId() { return userId; }
    public byte[] getEncryptedApiKey() { return Arrays.copyOf(encryptedApiKey, encryptedApiKey.length); }
    public byte[] getApiKeyIv() { return Arrays.copyOf(apiKeyIv, apiKeyIv.length); }
    public String getKeyHint() { return keyHint; }
}
