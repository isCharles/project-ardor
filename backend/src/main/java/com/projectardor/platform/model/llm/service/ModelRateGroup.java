package com.projectardor.platform.model.llm.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import com.projectardor.platform.model.llm.domain.LlmProvider;

/**
 * Which upstream budget a call spends.
 *
 * <p>Every provider Ardor reaches meters against the account or organisation, and several share one
 * budget across their models, so the bucket identity is the credential (provider + Base URL + key
 * digest) and never the model name alone. Two models on one key therefore contend for the same
 * request and token buckets, and renaming or adding a model cannot bypass the account limit.</p>
 *
 * <p>The API key is reduced to a truncated digest: these identifiers appear in Redis keys and metric
 * tags, so they must not carry the secret or the raw Base URL. {@code model} is only the suffix of
 * the model-level key, which keeps every bucket for one credential inside a single Redis hash tag.</p>
 */
public record ModelRateGroup(String account, String model) {

    /** Bucket for calls whose credential is not known; conservative, because it is shared. */
    public static ModelRateGroup unattributed() {
        return new ModelRateGroup(digest("unattributed"), "unknown");
    }

    public static ModelRateGroup of(LlmProvider provider, String baseUrl, String apiKey, String model) {
        String account = digest(String.join("|",
                provider == null ? "unknown" : provider.name(),
                normalizedBaseUrl(baseUrl),
                apiKey == null || apiKey.isBlank() ? "shared" : digest(apiKey)));
        String named = model == null || model.isBlank() ? "unknown" : model.strip().toLowerCase(Locale.ROOT);
        return new ModelRateGroup(account, digest(named));
    }

    private static String normalizedBaseUrl(String baseUrl) {
        return Optional.ofNullable(baseUrl).orElse("").strip().replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }

    static String digest(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("无法计算模型额度分组", unavailable);
        }
    }
}
