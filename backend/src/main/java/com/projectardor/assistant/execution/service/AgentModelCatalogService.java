package com.projectardor.assistant.execution.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.platform.model.integrations.domain.AuxiliaryServiceType;
import com.projectardor.platform.model.integrations.service.AuxiliaryApiConfigService;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import com.projectardor.platform.model.llm.service.LlmModelDiscoveryService;

/**
 * Resolves the user-selectable chat models. The primary model comes from the existing LLM
 * settings; the relay/fallback integration contributes every model ID it advertises, so the
 * composer can expose the same catalog users see in their relay dashboard.
 *
 * <p>Secrets never leave this service. A selected relay model reuses only that integration's
 * provider/base URL/key; the model ID is the only client-controlled part.</p>
 */
@Service
public class AgentModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AgentModelCatalogService.class);

    public static final String PRIMARY = "PRIMARY";
    public static final String FALLBACK = "FALLBACK";
    public static final String FALLBACK_PREFIX = "FALLBACK:";
    private static final int MAX_RELAY_MODELS = 300;
    private static final int MAX_CATALOG_CACHE_ENTRIES = 256;
    private static final long CATALOG_CACHE_TTL_MILLIS = 60_000;
    private static final int CATALOG_LOCK_STRIPES = 64;

    private final LlmConfigService llmConfigService;
    private final AuxiliaryApiConfigService auxiliaryConfigService;
    private final LlmModelDiscoveryService modelDiscoveryService;
    private final ConcurrentHashMap<UUID, CatalogEntry> catalogCache = new ConcurrentHashMap<>();
    private final Object[] catalogLocks = createLockStripes(CATALOG_LOCK_STRIPES);

    public AgentModelCatalogService(LlmConfigService llmConfigService,
            AuxiliaryApiConfigService auxiliaryConfigService,
            LlmModelDiscoveryService modelDiscoveryService) {
        this.llmConfigService = llmConfigService;
        this.auxiliaryConfigService = auxiliaryConfigService;
        this.modelDiscoveryService = modelDiscoveryService;
    }

    public List<Option> options(UUID userId) {
        CatalogEntry existing = catalogCache.get(userId);
        if (isFresh(existing)) return existing.options();
        synchronized (catalogLocks[Math.floorMod(userId.hashCode(), catalogLocks.length)]) {
            existing = catalogCache.get(userId);
            if (isFresh(existing)) return existing.options();
            if (catalogCache.size() >= MAX_CATALOG_CACHE_ENTRIES) catalogCache.clear();
            List<Option> options = loadOptions(userId);
            catalogCache.put(userId, new CatalogEntry(options, System.currentTimeMillis()));
            return options;
        }
    }

    public String descriptionFingerprint(UUID userId, String label) {
        String normalized = label == null ? "" : label.strip();
        Option option = findAvailableOption(userId, normalized).orElseGet(() -> {
            catalogCache.remove(userId);
            return findAvailableOption(userId, normalized)
                    .orElseThrow(() -> new IllegalArgumentException("未知的模型选项"));
        });
        return descriptionFingerprint(resolve(userId, option.id()).config());
    }

    private Optional<Option> findAvailableOption(UUID userId, String label) {
        return options(userId).stream()
                .filter(candidate -> candidate.available() && candidate.label().equals(label))
                .findFirst();
    }

    private static boolean isFresh(CatalogEntry entry) {
        return entry != null && entry.createdAt() + CATALOG_CACHE_TTL_MILLIS > System.currentTimeMillis();
    }

    private static Object[] createLockStripes(int size) {
        Object[] locks = new Object[size];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private static String descriptionFingerprint(LlmConfigService.LlmRuntimeConfig config) {
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

    private List<Option> loadOptions(UUID userId) {
        List<Option> options = new ArrayList<>();
        try {
            options.add(option(PRIMARY, llmConfigService.getRuntimeConfig(userId)));
        } catch (RuntimeException unavailable) {
            options.add(new Option(PRIMARY, "Primary model", "OPENAI_COMPATIBLE", false, false,
                    ModelReasoningPolicy.levels(new LlmConfigService.LlmRuntimeConfig(
                            LlmProvider.OPENAI_COMPATIBLE, "https://example.invalid", "unknown", "redacted"))));
        }
        auxiliaryConfigService.runtimeConfig(userId, AuxiliaryServiceType.FALLBACK_LLM)
                .ifPresentOrElse(fallback -> options.addAll(relayOptions(fallback)),
                        () -> options.add(new Option(FALLBACK, "Fallback model", "OPENAI_COMPATIBLE", false, false,
                                ModelReasoningPolicy.levels(new LlmConfigService.LlmRuntimeConfig(
                                        LlmProvider.OPENAI_COMPATIBLE, "https://example.invalid", "unknown", "redacted")))));
        return List.copyOf(options);
    }

    public Resolved resolve(UUID userId, String choice) {
        String normalized = choice == null ? PRIMARY : choice.strip().toUpperCase(Locale.ROOT);
        if (normalized.startsWith(FALLBACK_PREFIX)) {
            var fallback = auxiliaryConfigService.runtimeConfig(userId, AuxiliaryServiceType.FALLBACK_LLM)
                    .orElseThrow(() -> new IllegalArgumentException("备用模型尚未配置"));
            String model = choice.strip().substring(FALLBACK_PREFIX.length()).strip();
            if (model.isBlank()) throw new IllegalArgumentException("未知的模型选项");
            return new Resolved(FALLBACK, relayConfig(fallback, model));
        }
        if (FALLBACK.equals(normalized)) {
            var fallback = auxiliaryConfigService.runtimeConfig(userId, AuxiliaryServiceType.FALLBACK_LLM)
                    .orElseThrow(() -> new IllegalArgumentException("备用模型尚未配置"));
            return new Resolved(FALLBACK, fallback(fallback));
        }
        if (!PRIMARY.equals(normalized)) throw new IllegalArgumentException("未知的模型选项");
        return new Resolved(PRIMARY, llmConfigService.getRuntimeConfig(userId));
    }

    public boolean nativeControl(UUID userId, String choice) {
        return ModelReasoningPolicy.resolve(resolve(userId, choice).config(), ReasoningDepth.AUTO).nativeControl();
    }

    private List<Option> relayOptions(AuxiliaryApiConfigService.AuxiliaryRuntimeConfig raw) {
        List<String> models = relayModels(raw);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (raw.model() != null && !raw.model().isBlank()) unique.add(raw.model());
        for (String model : models) {
            if (model != null && !model.isBlank()) unique.add(model);
            if (unique.size() >= MAX_RELAY_MODELS) break;
        }
        return unique.stream().map(model -> {
            var config = relayConfig(raw, model);
            return new Option(FALLBACK_PREFIX + model, model, config.provider().name(), true,
                    ModelReasoningPolicy.resolve(config, ReasoningDepth.AUTO).nativeControl(),
                    ModelReasoningPolicy.levels(config));
        }).toList();
    }

    private List<String> relayModels(AuxiliaryApiConfigService.AuxiliaryRuntimeConfig raw) {
        LlmProvider configured = provider(raw.provider());
        LinkedHashSet<String> found = new LinkedHashSet<>();
        // A relay can split its catalog: Claude on the Anthropic endpoint, GPT/Seed/etc.
        // on the OpenAI-compatible /v1 endpoint, and occasionally a broader root list.
        if (configured == LlmProvider.ANTHROPIC_COMPATIBLE) {
            try {
                found.addAll(modelDiscoveryService.list(LlmProvider.OPENAI_COMPATIBLE,
                        openAiBase(raw.baseUrl()), raw.apiKey()));
            } catch (RuntimeException openAiDiscoveryFailure) {
                log.info("Relay OpenAI model discovery unavailable: provider={}, host={}, failure={}",
                        configured, host(raw.baseUrl()), openAiDiscoveryFailure.getClass().getSimpleName());
            }
            try {
                found.addAll(modelDiscoveryService.list(LlmProvider.OPENAI_COMPATIBLE,
                        raw.baseUrl(), raw.apiKey()));
            } catch (RuntimeException rootDiscoveryFailure) {
                log.info("Relay root model discovery unavailable: host={}, failure={}",
                        host(raw.baseUrl()), rootDiscoveryFailure.getClass().getSimpleName());
            }
        }
        try {
            found.addAll(modelDiscoveryService.list(configured, raw.baseUrl(), raw.apiKey()));
        } catch (RuntimeException configuredDiscoveryFailure) {
            log.info("Relay model discovery unavailable: provider={}, host={}, failure={}",
                    configured, host(raw.baseUrl()), configuredDiscoveryFailure.getClass().getSimpleName());
        }
        if (found.isEmpty() && raw.model() != null && !raw.model().isBlank()) found.add(raw.model());
        return new ArrayList<>(found);
    }

    private LlmConfigService.LlmRuntimeConfig relayConfig(
            AuxiliaryApiConfigService.AuxiliaryRuntimeConfig raw, String model) {
        String selected = model == null || model.isBlank() ? raw.model() : model;
        if (provider(raw.provider()) == LlmProvider.ANTHROPIC_COMPATIBLE && !isAnthropicModel(selected)) {
            return new LlmConfigService.LlmRuntimeConfig(LlmProvider.OPENAI_COMPATIBLE,
                    openAiBase(raw.baseUrl()), selected, raw.apiKey());
        }
        return new LlmConfigService.LlmRuntimeConfig(provider(raw.provider()), raw.baseUrl(), selected, raw.apiKey());
    }

    private static String openAiBase(String baseUrl) {
        String normalized = Optional.ofNullable(baseUrl).orElse("").replaceAll("/+$", "");
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }

    private static String host(String baseUrl) {
        try {
            return java.net.URI.create(Optional.ofNullable(baseUrl).orElse("")).getHost();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean isAnthropicModel(String model) {
        String normalized = Optional.ofNullable(model).orElse("").toLowerCase(Locale.ROOT);
        return normalized.contains("claude") || normalized.contains("opus")
                || normalized.contains("sonnet") || normalized.contains("haiku");
    }

    private Option option(String id, LlmConfigService.LlmRuntimeConfig config) {
        return new Option(id, config.model(), config.provider().name(),
                true, ModelReasoningPolicy.resolve(config, ReasoningDepth.AUTO).nativeControl(),
                ModelReasoningPolicy.levels(config));
    }

    private LlmConfigService.LlmRuntimeConfig fallback(AuxiliaryApiConfigService.AuxiliaryRuntimeConfig config) {
        return new LlmConfigService.LlmRuntimeConfig(provider(config.provider()), config.baseUrl(),
                config.model(), config.apiKey());
    }

    private static LlmProvider provider(String raw) {
        String normalized = Optional.ofNullable(raw).orElse("").strip().toUpperCase(Locale.ROOT);
        return normalized.contains("ANTHROPIC") ? LlmProvider.ANTHROPIC_COMPATIBLE : LlmProvider.OPENAI_COMPATIBLE;
    }

    public record Option(String id, String label, String provider, boolean available, boolean nativeControl,
            List<ModelReasoningPolicy.Level> reasoningLevels) {
    }

    public record Resolved(String id, LlmConfigService.LlmRuntimeConfig config) {
    }

    private record CatalogEntry(List<Option> options, long createdAt) {}
}
