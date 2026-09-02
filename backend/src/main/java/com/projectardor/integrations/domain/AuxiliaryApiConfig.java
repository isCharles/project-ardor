package com.projectardor.integrations.domain;

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
@Table(name = "auxiliary_api_configs")
public class AuxiliaryApiConfig {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 32)
    private AuxiliaryServiceType serviceType;

    @Column(nullable = false, length = 120)
    private String provider;

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

    protected AuxiliaryApiConfig() {
    }

    private AuxiliaryApiConfig(UUID userId, AuxiliaryServiceType serviceType) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.serviceType = serviceType;
    }

    public static AuxiliaryApiConfig create(UUID userId, AuxiliaryServiceType serviceType) {
        return new AuxiliaryApiConfig(userId, serviceType);
    }

    public void update(String provider, String baseUrl, String model, byte[] ciphertext, byte[] iv, String keyHint) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.encryptedApiKey = Arrays.copyOf(ciphertext, ciphertext.length);
        this.apiKeyIv = Arrays.copyOf(iv, iv.length);
        this.keyHint = keyHint;
    }

    public void updateConnection(String provider, String baseUrl, String model) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @PrePersist
    void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public AuxiliaryServiceType getServiceType() { return serviceType; }
    public String getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public String getKeyHint() { return keyHint; }
    public byte[] getEncryptedApiKey() { return Arrays.copyOf(encryptedApiKey, encryptedApiKey.length); }
    public byte[] getApiKeyIv() { return Arrays.copyOf(apiKeyIv, apiKeyIv.length); }
}
