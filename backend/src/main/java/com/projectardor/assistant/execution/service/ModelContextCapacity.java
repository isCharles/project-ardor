package com.projectardor.assistant.execution.service;

import java.net.URI;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.projectardor.platform.model.llm.service.LlmConfigService;

/** Exact configured IDs only. Relay aliases never inherit an official endpoint's limit. */
@Component
public final class ModelContextCapacity {
    private final Environment environment;
    private final UserModelSettingsStore settingsStore;

    /** Compatibility for tests and non-Spring construction. */
    public ModelContextCapacity(Environment environment) {
        this(environment, null);
    }

    @Autowired
    public ModelContextCapacity(Environment environment, UserModelSettingsStore settingsStore) {
        this.environment = environment;
        this.settingsStore = settingsStore;
    }

    public Long resolve(LlmConfigService.LlmRuntimeConfig config) {
        return resolve(null, config);
    }

    /** A user's explicit per-model window takes precedence over environment and built-in limits. */
    public Long resolve(UUID userId, LlmConfigService.LlmRuntimeConfig config) {
        Long userWindow = userConfiguredWindow(userId, config);
        if (userWindow != null) return userWindow;
        if (config == null || config.model() == null) return null;
        String model = config.model();
        String configured = environment.getProperty("app.agent.context.windows." + model);
        if (configured != null) {
            try {
                long limit = Long.parseLong(configured.strip());
                return limit > 0 ? limit : null;
            } catch (NumberFormatException ignored) { return null; }
        }
        String host;
        try { host = URI.create(config.baseUrl()).getHost(); }
        catch (RuntimeException invalid) { return null; }
        // Verified 2026-10-25: official pricing lists 1M; published V4 Flash config
        // specifies max_position_embeddings=1048576. No prefix/wildcard matching.
        // https://api-docs.deepseek.com/quick_start/pricing/
        // https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/blob/main/config.json
        if ("api.deepseek.com".equalsIgnoreCase(host) && "deepseek-v4-flash".equals(model))
            return 1_048_576L;
        return null;
    }

    private Long userConfiguredWindow(UUID userId, LlmConfigService.LlmRuntimeConfig config) {
        if (userId == null || settingsStore == null || config == null || config.model() == null) return null;
        try {
            Long value = settingsStore.findContextWindow(userId, ModelConfigFingerprint.of(config));
            return value != null && value > 0 ? value : null;
        } catch (RuntimeException unavailable) {
            // A settings lookup must never block a request; fall back to the built-in limit.
            return null;
        }
    }
}
