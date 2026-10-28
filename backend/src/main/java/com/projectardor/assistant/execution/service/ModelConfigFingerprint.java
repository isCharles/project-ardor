package com.projectardor.assistant.execution.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import com.projectardor.platform.model.llm.service.LlmConfigService;

public final class ModelConfigFingerprint {
    private ModelConfigFingerprint() {}

    public static String of(LlmConfigService.LlmRuntimeConfig config) {
        String material = config.provider().name() + "|" + normalizedBaseUrl(config.baseUrl())
                + "|" + Optional.ofNullable(config.model()).orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("无法计算模型配置指纹", unavailable);
        }
    }

    private static String normalizedBaseUrl(String baseUrl) {
        return Optional.ofNullable(baseUrl).orElse("").strip().replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }
}
