package com.projectardor.websearch.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.llm.security.ApiKeyCipher;
import com.projectardor.websearch.domain.WebSearchConfig;
import com.projectardor.websearch.repository.WebSearchConfigRepository;
import com.projectardor.websearch.web.WebSearchConfigRequest;
import com.projectardor.websearch.web.WebSearchConfigResponse;

@Service
public class WebSearchConfigService {

    private final WebSearchConfigRepository repository;
    private final ApiKeyCipher cipher;

    public WebSearchConfigService(WebSearchConfigRepository repository, ApiKeyCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public WebSearchConfigResponse get(UUID userId) {
        return repository.findByUserId(userId)
                .map(WebSearchConfigResponse::configured)
                .orElseGet(WebSearchConfigResponse::unconfigured);
    }

    @Transactional(readOnly = true)
    public String requireApiKey(UUID userId) {
        WebSearchConfig config = repository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("请先在设置中配置 Tavily API Key"));
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
