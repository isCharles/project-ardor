package com.projectardor.admin.domain;

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
@Table(name = "system_api_configs")
public class SystemApiConfig {
    @Id @Enumerated(EnumType.STRING) @Column(name = "service_type", length = 32)
    private SystemApiServiceType serviceType;
    @Column(nullable = false, length = 120) private String provider;
    @Column(name = "base_url", length = 512) private String baseUrl;
    @Column(length = 160) private String model;
    @Column(name = "encrypted_api_key", nullable = false) private byte[] encryptedApiKey;
    @Column(name = "api_key_iv", nullable = false) private byte[] apiKeyIv;
    @Column(name = "key_hint", nullable = false, length = 16) private String keyHint;
    @Column(name = "updated_by") private UUID updatedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected SystemApiConfig() {}

    public static SystemApiConfig create(SystemApiServiceType type) {
        SystemApiConfig config = new SystemApiConfig();
        config.serviceType = type;
        return config;
    }

    public void update(String provider, String baseUrl, String model, byte[] ciphertext, byte[] iv,
            String keyHint, UUID updatedBy) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.encryptedApiKey = Arrays.copyOf(ciphertext, ciphertext.length);
        this.apiKeyIv = Arrays.copyOf(iv, iv.length);
        this.keyHint = keyHint;
        this.updatedBy = updatedBy;
    }

    public void updateConnection(String provider, String baseUrl, String model, UUID updatedBy) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.updatedBy = updatedBy;
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public SystemApiServiceType getServiceType() { return serviceType; }
    public String getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public byte[] getEncryptedApiKey() { return Arrays.copyOf(encryptedApiKey, encryptedApiKey.length); }
    public byte[] getApiKeyIv() { return Arrays.copyOf(apiKeyIv, apiKeyIv.length); }
    public String getKeyHint() { return keyHint; }
    public Instant getUpdatedAt() { return updatedAt; }
}
