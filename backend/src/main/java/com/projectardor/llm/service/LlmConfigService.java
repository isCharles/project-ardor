package com.projectardor.llm.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.llm.domain.LlmProvider;
import com.projectardor.llm.domain.LlmProviderConfig;
import com.projectardor.llm.repository.LlmProviderConfigRepository;
import com.projectardor.llm.security.ApiKeyCipher;
import com.projectardor.llm.web.LlmConfigResponse;
import com.projectardor.llm.web.LlmConfigUpdateRequest;
import com.projectardor.common.security.ExternalBaseUrlPolicy;

@Service
public class LlmConfigService {

    private final LlmProviderConfigRepository repository;
    private final ApiKeyCipher cipher;
    private final ExternalBaseUrlPolicy externalBaseUrlPolicy;

    public LlmConfigService(
            LlmProviderConfigRepository repository,
            ApiKeyCipher cipher,
            ExternalBaseUrlPolicy externalBaseUrlPolicy) {
        this.repository = repository;
        this.cipher = cipher;
        this.externalBaseUrlPolicy = externalBaseUrlPolicy;
    }

    @Transactional(readOnly = true)
    public LlmConfigResponse get(UUID userId) {
        return repository.findByUserId(userId)
                .map(LlmConfigResponse::configured)
                .orElseGet(LlmConfigResponse::unconfigured);
    }

    @Transactional(readOnly = true)
    public LlmRuntimeConfig getRuntimeConfig(UUID userId) {
        LlmProviderConfig config = repository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("请先配置 LLM 服务和 API Key"));
        String apiKey = cipher.decrypt(
                userId,
                config.getEncryptedApiKey(),
                config.getApiKeyIv());
        return new LlmRuntimeConfig(
                config.getProvider(),
                normalizeBaseUrl(config.getBaseUrl()),
                config.getModel(),
                apiKey);
    }

    @Transactional(readOnly = true)
    public LlmRuntimeConfig runtimeConfigForTest(UUID userId, LlmConfigUpdateRequest request) {
        String apiKey = request.apiKey() == null ? "" : request.apiKey().strip();
        if (apiKey.isBlank()) {
            LlmProviderConfig existing = repository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalArgumentException("请填写 API Key 后再测试连接"));
            apiKey = cipher.decrypt(userId, existing.getEncryptedApiKey(), existing.getApiKeyIv());
        }
        return new LlmRuntimeConfig(
                request.provider(), normalizeBaseUrl(request.baseUrl()), request.model().strip(), apiKey);
    }

    @Transactional
    public LlmConfigResponse upsert(UUID userId, LlmConfigUpdateRequest request) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String model = request.model().strip();
        var existing = repository.findByUserId(userId);
        LlmProviderConfig config = existing.orElseGet(() -> LlmProviderConfig.create(userId));

        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            String apiKey = request.apiKey().strip();
            ApiKeyCipher.EncryptedValue encrypted = cipher.encrypt(userId, apiKey);
            config.update(
                    request.provider(), baseUrl, model,
                    encrypted.ciphertext(), encrypted.iv(), keyHint(apiKey));
        } else if (existing.isPresent()) {
            config.updateConnection(request.provider(), baseUrl, model);
        } else {
            throw new IllegalArgumentException("首次配置时必须填写 API Key");
        }

        return LlmConfigResponse.configured(repository.save(config));
    }

    @Transactional
    public void delete(UUID userId) {
        repository.deleteByUserId(userId);
    }

    private String normalizeBaseUrl(String rawBaseUrl) {
        return externalBaseUrlPolicy.normalizeAndValidate(rawBaseUrl);
    }

    private String keyHint(String apiKey) {
        int start = Math.max(0, apiKey.length() - 4);
        return "••••" + apiKey.substring(start);
    }

    public record LlmRuntimeConfig(
            LlmProvider provider,
            String baseUrl,
            String model,
            String apiKey) {
    }
}
