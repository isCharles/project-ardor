package com.projectardor.websearch.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.llm.security.ApiKeyCipher;
import com.projectardor.websearch.domain.WebSearchConfig;
import com.projectardor.websearch.repository.WebSearchConfigRepository;
import com.projectardor.websearch.web.WebSearchConfigRequest;
import com.projectardor.websearch.web.WebSearchConfigResponse;
import com.projectardor.admin.domain.SystemApiServiceType;
import com.projectardor.admin.service.SystemApiConfigService;

@Service
public class WebSearchConfigService {

    private final WebSearchConfigRepository repository;
    private final ApiKeyCipher cipher;
    private final SystemApiConfigService systemConfigService;

    public WebSearchConfigService(WebSearchConfigRepository repository, ApiKeyCipher cipher,
            SystemApiConfigService systemConfigService) {
        this.repository = repository;
        this.cipher = cipher;
        this.systemConfigService = systemConfigService;
    }

    @Transactional(readOnly = true)
    public WebSearchConfigResponse get(UUID userId) {
        return repository.findByUserId(userId)
                .map(WebSearchConfigResponse::configured)
                .orElseGet(() -> systemConfigService.view(SystemApiServiceType.WEB_SEARCH)
                        .map(view -> WebSearchConfigResponse.inherited(view.keyHint()))
                        .orElseGet(WebSearchConfigResponse::unconfigured));
    }

    @Transactional(readOnly = true)
    public String requireApiKey(UUID userId) {
        var personal = repository.findByUserId(userId);
        if (personal.isEmpty()) {
            return systemConfigService.webSearchApiKey()
                    .orElseThrow(() -> new IllegalStateException("管理员尚未配置联网搜索，您也可以在设置中添加个人配置"));
        }
        WebSearchConfig config = personal.get();
        return cipher.decrypt(userId, config.getEncryptedApiKey(), config.getApiKeyIv());
    }

    @Transactional(readOnly = true)
    public String apiKeyForTest(UUID userId, WebSearchConfigRequest request) {
        String supplied = normalizeKey(request.apiKey());
        return supplied.isBlank() ? requireApiKey(userId) : supplied;
    }

    @Transactional
    public WebSearchConfigResponse upsert(UUID userId, WebSearchConfigRequest request) {
        String apiKey = normalizeKey(request.apiKey());
        if (apiKey.isBlank()) {
            if (repository.findByUserId(userId).isPresent()) return get(userId);
            throw new IllegalArgumentException("首次配置时必须填写 Tavily API Key");
        }
        WebSearchConfig config = repository.findByUserId(userId)
                .orElseGet(() -> WebSearchConfig.create(userId));
        ApiKeyCipher.EncryptedValue encrypted = cipher.encrypt(userId, apiKey);
        config.update(encrypted.ciphertext(), encrypted.iv(), keyHint(apiKey));
        return WebSearchConfigResponse.configured(repository.save(config));
    }

    @Transactional
    public void delete(UUID userId) {
        repository.deleteByUserId(userId);
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.strip();
    }

    private String keyHint(String apiKey) {
        int start = Math.max(0, apiKey.length() - 4);
        return "••••" + apiKey.substring(start);
    }
}
