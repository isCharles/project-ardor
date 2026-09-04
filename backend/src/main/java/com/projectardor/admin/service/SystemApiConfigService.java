package com.projectardor.admin.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.admin.domain.SystemApiConfig;
import com.projectardor.admin.domain.SystemApiServiceType;
import com.projectardor.admin.repository.SystemApiConfigRepository;
import com.projectardor.admin.web.AdminApiConfigRequest;
import com.projectardor.admin.web.AdminApiConfigResponse;
import com.projectardor.common.security.ExternalBaseUrlPolicy;
import com.projectardor.integrations.service.AuxiliaryApiConfigService.AuxiliaryRuntimeConfig;
import com.projectardor.llm.domain.LlmProvider;
import com.projectardor.llm.security.ApiKeyCipher;
import com.projectardor.llm.service.LlmConfigService.LlmRuntimeConfig;

@Service
public class SystemApiConfigService {
    private static final UUID SYSTEM_SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final SystemApiConfigRepository repository;
    private final ApiKeyCipher cipher;
    private final ExternalBaseUrlPolicy externalBaseUrlPolicy;

    public SystemApiConfigService(SystemApiConfigRepository repository, ApiKeyCipher cipher,
            ExternalBaseUrlPolicy externalBaseUrlPolicy) {
        this.repository = repository;
        this.cipher = cipher;
        this.externalBaseUrlPolicy = externalBaseUrlPolicy;
    }

    @Transactional(readOnly = true)
    public List<AdminApiConfigResponse> list() {
        return Arrays.stream(SystemApiServiceType.values())
                .map(type -> repository.findById(type).map(AdminApiConfigResponse::configured)
                        .orElseGet(() -> AdminApiConfigResponse.unconfigured(type)))
                .toList();
    }

    @Transactional
    public AdminApiConfigResponse upsert(UUID adminId, SystemApiServiceType type, AdminApiConfigRequest request) {
        String provider = request.provider().strip();
        String baseUrl = type == SystemApiServiceType.WEB_SEARCH
                ? "https://api.tavily.com"
                : requiredBaseUrl(request.baseUrl());
        String model = type == SystemApiServiceType.WEB_SEARCH ? null : requiredModel(request.model());
        Optional<SystemApiConfig> existing = repository.findById(type);
        SystemApiConfig config = existing.orElseGet(() -> SystemApiConfig.create(type));
        String apiKey = request.apiKey() == null ? "" : request.apiKey().strip();
        if (!apiKey.isBlank()) {
            ApiKeyCipher.EncryptedValue encrypted = cipher.encrypt(SYSTEM_SCOPE, apiKey);
            config.update(provider, baseUrl, model, encrypted.ciphertext(), encrypted.iv(), keyHint(apiKey), adminId);
        } else if (existing.isPresent()) {
            config.updateConnection(provider, baseUrl, model, adminId);
        } else {
            throw new IllegalArgumentException("首次配置时必须填写 API Key");
        }
        return AdminApiConfigResponse.configured(repository.save(config));
    }

    @Transactional
    public void delete(SystemApiServiceType type) { repository.deleteById(type); }

    @Transactional(readOnly = true)
    public Optional<LlmRuntimeConfig> primaryLlm() {
        return repository.findById(SystemApiServiceType.PRIMARY_LLM).map(config -> new LlmRuntimeConfig(
                parseLlmProvider(config.getProvider()), config.getBaseUrl(), config.getModel(), decrypt(config)));
    }

    @Transactional(readOnly = true)
    public Optional<AuxiliaryRuntimeConfig> auxiliary(SystemApiServiceType type) {
        if (type == SystemApiServiceType.PRIMARY_LLM || type == SystemApiServiceType.WEB_SEARCH) {
            throw new IllegalArgumentException("该类型不是辅助模型服务");
        }
        return repository.findById(type).map(config -> new AuxiliaryRuntimeConfig(
                config.getProvider(), config.getBaseUrl(), config.getModel(), decrypt(config)));
    }

    @Transactional(readOnly = true)
    public Optional<String> webSearchApiKey() {
        return repository.findById(SystemApiServiceType.WEB_SEARCH).map(this::decrypt);
    }

    @Transactional(readOnly = true)
    public Optional<AdminApiConfigResponse> view(SystemApiServiceType type) {
        return repository.findById(type).map(AdminApiConfigResponse::configured);
    }

    @Transactional(readOnly = true)
    public LlmRuntimeConfig llmForTest(SystemApiServiceType type, AdminApiConfigRequest request) {
        if (type != SystemApiServiceType.PRIMARY_LLM) throw new IllegalArgumentException("该类型不是主 LLM");
        return new LlmRuntimeConfig(parseLlmProvider(request.provider()), requiredBaseUrl(request.baseUrl()),
                requiredModel(request.model()), suppliedOrSaved(type, request.apiKey()));
    }

    @Transactional(readOnly = true)
    public AuxiliaryRuntimeConfig auxiliaryForTest(SystemApiServiceType type, AdminApiConfigRequest request) {
        if (type == SystemApiServiceType.PRIMARY_LLM || type == SystemApiServiceType.WEB_SEARCH) {
            throw new IllegalArgumentException("该类型不是辅助模型服务");
        }
        return new AuxiliaryRuntimeConfig(request.provider().strip(), requiredBaseUrl(request.baseUrl()),
                requiredModel(request.model()), suppliedOrSaved(type, request.apiKey()));
    }

    @Transactional(readOnly = true)
    public String webSearchKeyForTest(AdminApiConfigRequest request) {
        return suppliedOrSaved(SystemApiServiceType.WEB_SEARCH, request.apiKey());
    }

    private String suppliedOrSaved(SystemApiServiceType type, String supplied) {
        String key = supplied == null ? "" : supplied.strip();
        if (!key.isBlank()) return key;
        return repository.findById(type).map(this::decrypt)
                .orElseThrow(() -> new IllegalArgumentException("请填写 API Key 后再测试连接"));
    }

    private String decrypt(SystemApiConfig config) {
        return cipher.decrypt(SYSTEM_SCOPE, config.getEncryptedApiKey(), config.getApiKeyIv());
    }

    private String requiredBaseUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Base URL 不能为空");
        return externalBaseUrlPolicy.normalizeAndValidate(value);
    }

    private String requiredModel(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("模型名称不能为空");
        return value.strip();
    }

    private LlmProvider parseLlmProvider(String value) {
        try { return LlmProvider.valueOf(value.strip().toUpperCase()); }
        catch (RuntimeException exception) { throw new IllegalStateException("管理员主模型协议配置无效"); }
    }

    private String keyHint(String apiKey) {
        return "••••" + apiKey.substring(Math.max(0, apiKey.length() - 4));
    }
}
