package com.projectardor.admin.web;

import java.time.Instant;

import com.projectardor.admin.domain.SystemApiConfig;
import com.projectardor.admin.domain.SystemApiServiceType;

public record AdminApiConfigResponse(
        SystemApiServiceType serviceType,
        boolean configured,
        String provider,
        String baseUrl,
        String model,
        String keyHint,
        Instant updatedAt) {

    public static AdminApiConfigResponse configured(SystemApiConfig config) {
        return new AdminApiConfigResponse(config.getServiceType(), true, config.getProvider(),
                config.getBaseUrl(), config.getModel(), config.getKeyHint(), config.getUpdatedAt());
    }

    public static AdminApiConfigResponse unconfigured(SystemApiServiceType type) {
        return new AdminApiConfigResponse(type, false, defaultProvider(type), defaultBaseUrl(type),
                defaultModel(type), null, null);
    }

    private static String defaultProvider(SystemApiServiceType type) {
        return switch (type) {
            case PRIMARY_LLM -> "OPENAI_COMPATIBLE";
            case FALLBACK_LLM -> "OpenAI";
            case WEB_SEARCH -> "Tavily";
            default -> "OpenAI";
        };
    }

    private static String defaultBaseUrl(SystemApiServiceType type) {
        return type == SystemApiServiceType.WEB_SEARCH ? "https://api.tavily.com" : "https://api.openai.com/v1";
    }

    private static String defaultModel(SystemApiServiceType type) {
        return type == SystemApiServiceType.PRIMARY_LLM ? "gpt-4.1-mini" : "";
    }
}
