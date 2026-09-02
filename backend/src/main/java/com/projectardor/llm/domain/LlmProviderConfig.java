package com.projectardor.llm.domain;

import java.time.Instant;
import java.util.Arrays;
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
@Table(name = "llm_provider_configs")
public class LlmProviderConfig {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private LlmProvider provider;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(nullable = false, length = 160)
    private String model;

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

    protected LlmProviderConfig() {
    }

    private LlmProviderConfig(UUID userId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
    }

    public static LlmProviderConfig create(UUID userId) {
        return new LlmProviderConfig(userId);
    }

    public void update(
            LlmProvider provider,
            String baseUrl,
            String model,
            byte[] encryptedApiKey,
            byte[] apiKeyIv,
            String keyHint) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.encryptedApiKey = Arrays.copyOf(encryptedApiKey, encryptedApiKey.length);
        this.apiKeyIv = Arrays.copyOf(apiKeyIv, apiKeyIv.length);
        this.keyHint = keyHint;
    }

    public void updateConnection(LlmProvider provider, String baseUrl, String model) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
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

    public UUID getUserId() {
        return userId;
    }

    public LlmProvider getProvider() {
        return provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public byte[] getEncryptedApiKey() {
        return Arrays.copyOf(encryptedApiKey, encryptedApiKey.length);
    }

    public byte[] getApiKeyIv() {
        return Arrays.copyOf(apiKeyIv, apiKeyIv.length);
    }

    public String getKeyHint() {
        return keyHint;
    }
}
