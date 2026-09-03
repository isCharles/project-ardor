package com.projectardor.integrations.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.integrations.domain.AuxiliaryApiConfig;
import com.projectardor.integrations.domain.AuxiliaryServiceType;
import com.projectardor.integrations.repository.AuxiliaryApiConfigRepository;
import com.projectardor.integrations.web.AuxiliaryApiConfigRequest;
import com.projectardor.integrations.web.AuxiliaryApiConfigResponse;
import com.projectardor.llm.security.ApiKeyCipher;
import com.projectardor.common.security.ExternalBaseUrlPolicy;

@Service
public class AuxiliaryApiConfigService {

    private final AuxiliaryApiConfigRepository repository;
    private final ApiKeyCipher cipher;
    private final ExternalBaseUrlPolicy externalBaseUrlPolicy;

    public AuxiliaryApiConfigService(
            AuxiliaryApiConfigRepository repository,
            ApiKeyCipher cipher,
            ExternalBaseUrlPolicy externalBaseUrlPolicy) {
        this.repository = repository;
        this.cipher = cipher;
        this.externalBaseUrlPolicy = externalBaseUrlPolicy;
    }

    @Transactional(readOnly = true)
    public List<AuxiliaryApiConfigResponse> list(UUID userId) {
        Map<AuxiliaryServiceType, AuxiliaryApiConfig> existing = repository.findAllByUserId(userId).stream()
                .collect(Collectors.toMap(AuxiliaryApiConfig::getServiceType, Function.identity()));
        return Arrays.stream(AuxiliaryServiceType.values())
                .map(type -> {
                    AuxiliaryApiConfig config = existing.get(type);
                    return config == null
                            ? AuxiliaryApiConfigResponse.unconfigured(type)
                            : AuxiliaryApiConfigResponse.configured(config);
                })
                .toList();
    }

    @Transactional
    public AuxiliaryApiConfigResponse upsert(
            UUID userId,
            AuxiliaryServiceType serviceType,
            AuxiliaryApiConfigRequest request) {
        String provider = request.provider().strip();
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String model = request.model().strip();
        var existing = repository.findByUserIdAndServiceType(userId, serviceType);
        AuxiliaryApiConfig config = existing.orElseGet(() -> AuxiliaryApiConfig.create(userId, serviceType));

        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            String apiKey = request.apiKey().strip();
            ApiKeyCipher.EncryptedValue encrypted = cipher.encrypt(userId, apiKey);
            config.update(provider, baseUrl, model, encrypted.ciphertext(), encrypted.iv(), keyHint(apiKey));
        } else if (existing.isPresent()) {
            config.updateConnection(provider, baseUrl, model);
        } else {
            throw new IllegalArgumentException("首次配置时必须填写 API Key");
        }

        return AuxiliaryApiConfigResponse.configured(repository.save(config));
    }

    @Transactional(readOnly = true)
    public AuxiliaryRuntimeConfig runtimeConfigForTest(
            UUID userId,
            AuxiliaryServiceType serviceType,
            AuxiliaryApiConfigRequest request) {
        String apiKey = request.apiKey() == null ? "" : request.apiKey().strip();
        if (apiKey.isBlank()) {
            AuxiliaryApiConfig existing = repository.findByUserIdAndServiceType(userId, serviceType)
                    .orElseThrow(() -> new IllegalArgumentException("请填写 API Key 后再测试连接"));
            apiKey = cipher.decrypt(userId, existing.getEncryptedApiKey(), existing.getApiKeyIv());
        }
        return new AuxiliaryRuntimeConfig(
                request.provider().strip(),
                normalizeBaseUrl(request.baseUrl()),
                request.model().strip(),
                apiKey);
    }

    /**
     * Resolves the saved configuration for one reserved service so runtime callers
     * (embeddings, ASR, TTS) can use it. The API key is decrypted with the owning
     * user's id as additional authenticated data, so a config can only ever be
     * unlocked for the user it belongs to.
     */
    @Transactional(readOnly = true)
    public Optional<AuxiliaryRuntimeConfig> runtimeConfig(UUID userId, AuxiliaryServiceType serviceType) {
        return repository.findByUserIdAndServiceType(userId, serviceType)
                .map(config -> new AuxiliaryRuntimeConfig(
                        config.getProvider(),
                        normalizeBaseUrl(config.getBaseUrl()),
                        config.getModel(),
                        cipher.decrypt(userId, config.getEncryptedApiKey(), config.getApiKeyIv())));
    }

    @Transactional
    public void delete(UUID userId, AuxiliaryServiceType serviceType) {
        repository.findByUserIdAndServiceType(userId, serviceType).ifPresent(repository::delete);
    }

    private String normalizeBaseUrl(String rawBaseUrl) {
        return externalBaseUrlPolicy.normalizeAndValidate(rawBaseUrl);
    }

    private String keyHint(String apiKey) {
        int start = Math.max(0, apiKey.length() - 4);
        return "••••" + apiKey.substring(start);
    }

    public record AuxiliaryRuntimeConfig(String provider, String baseUrl, String model, String apiKey) {
    }
}
