package com.projectardor.assistant.execution.service;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import com.projectardor.platform.model.llm.service.LlmConfigService;

/** Exact configured IDs only. Relay aliases never inherit an official endpoint's limit. */
@Component
public final class ModelContextCapacity {
    private final Environment environment;

    public ModelContextCapacity(Environment environment) { this.environment = environment; }

    public Long resolve(LlmConfigService.LlmRuntimeConfig config) {
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
}
