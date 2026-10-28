package com.projectardor.assistant.execution.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final long DESCRIPTION_REFRESH_COOLDOWN_MILLIS = 60_000;
    private static final int CATALOG_LOCK_STRIPES = 64;

    private static final Set<String> THINKING_EFFORTS = Set.of("AUTO", "QUICK", "STANDARD", "DEEP");
    private static final long MIN_CONTEXT_WINDOW = 32_768;
    private static final long MAX_CONTEXT_WINDOW = 10_000_000;

    private final LlmConfigService llmConfigService;
    private final AuxiliaryApiConfigService auxiliaryConfigService;
    private final LlmModelDiscoveryService modelDiscoveryService;
    private final UserModelSettingsStore settingsStore;
    private final ConcurrentHashMap<UUID, CatalogEntry> catalogCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RelaySnapshot> relaySnapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> descriptionRefreshAt = new ConcurrentHashMap<>();
    private final Object[] catalogLocks = createLockStripes(CATALOG_LOCK_STRIPES);

    /** Kept for direct unit construction; production uses the Spring constructor below. */
    public AgentModelCatalogService(LlmConfigService llmConfigService,
            AuxiliaryApiConfigService auxiliaryConfigService,
            LlmModelDiscoveryService modelDiscoveryService) {
        this(llmConfigService, auxiliaryConfigService, modelDiscoveryService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentModelCatalogService(LlmConfigService llmConfigService,
            AuxiliaryApiConfigService auxiliaryConfigService,
            LlmModelDiscoveryService modelDiscoveryService,
            UserModelSettingsStore settingsStore) {
        this.llmConfigService = llmConfigService;
        this.auxiliaryConfigService = auxiliaryConfigService;
        this.modelDiscoveryService = modelDiscoveryService;
        this.settingsStore = settingsStore;
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
            refreshCatalogForDescription(userId);
            return findAvailableOption(userId, normalized)
                    .orElseThrow(() -> new IllegalArgumentException("未知的模型选项"));
        });
        return descriptionFingerprint(resolve(userId, option.id()).config());
    }

    private void refreshCatalogForDescription(UUID userId) {
        Object lock = catalogLocks[Math.floorMod(userId.hashCode(), catalogLocks.length)];
        synchronized (lock) {
            long now = System.currentTimeMillis();
            Long last = descriptionRefreshAt.get(userId);
            if (last != null && now - last < DESCRIPTION_REFRESH_COOLDOWN_MILLIS) return;
            if (descriptionRefreshAt.size() >= MAX_CATALOG_CACHE_ENTRIES) descriptionRefreshAt.clear();
            descriptionRefreshAt.put(userId, now);
            catalogCache.remove(userId);
        }
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
        return ModelConfigFingerprint.of(config);
    }

    private Map<String, UserModelSettingsStore.Setting> settingsFor(UUID userId) {
        if (settingsStore == null) return Map.of();
        try {
            return settingsStore.findAll(userId);
        } catch (RuntimeException unavailable) {
            log.warn("User model settings unavailable: {}", unavailable.getClass().getSimpleName());
            return Map.of();
        }
    }

    private List<Option> loadOptions(UUID userId) {
        Map<String, UserModelSettingsStore.Setting> settings = settingsFor(userId);
        List<Option> options = new ArrayList<>();
        try {
            options.add(option(PRIMARY, llmConfigService.getRuntimeConfig(userId), settings));
        } catch (RuntimeException unavailable) {
            options.add(new Option(PRIMARY, "Primary model", "OPENAI_COMPATIBLE", false, false,
                    ModelReasoningPolicy.levels(new LlmConfigService.LlmRuntimeConfig(
                            LlmProvider.OPENAI_COMPATIBLE, "https://example.invalid", "unknown", "redacted"))));
        }
        auxiliaryConfigService.runtimeConfig(userId, AuxiliaryServiceType.FALLBACK_LLM)
                .ifPresentOrElse(fallback -> options.addAll(relayOptions(userId, fallback, settings)),
                        () -> options.add(new Option(FALLBACK, "Fallback model", "OPENAI_COMPATIBLE", false, false,
                                ModelReasoningPolicy.levels(new LlmConfigService.LlmRuntimeConfig(
                                        LlmProvider.OPENAI_COMPATIBLE, "https://example.invalid", "unknown", "redacted")))));
        return List.copyOf(options);
    }

    public List<Option> updateSettings(UUID userId, List<SettingsUpdate> updates) {
        if (settingsStore == null) throw new IllegalStateException("模型设置暂不可用");
        if (updates == null || updates.isEmpty()) throw new IllegalArgumentException("没有需要保存的模型设置");
        if (updates.size() > MAX_RELAY_MODELS) throw new IllegalArgumentException("一次保存的模型过多");
        List<SettingsUpdate> validated = updates.stream().map(this::validated).toList();
        List<Prepared> prepared = validated.stream().map(update -> {
            var config = resolve(userId, update.modelChoice()).config();
            return new Prepared(update, config);
        }).toList();
        for (Prepared item : prepared) {
            var update = item.update();
            var config = item.config();
            settingsStore.save(userId, ModelConfigFingerprint.of(config), config.provider().name(),
                    Optional.ofNullable(config.baseUrl()).orElse(""), Optional.ofNullable(config.model()).orElse(""),
                    update.visible() == null || update.visible(), update.thinkingEffort(),
                    update.contextWindowTokens());
        }
        catalogCache.remove(userId);
        return options(userId);
    }

    private SettingsUpdate validated(SettingsUpdate update) {
        if (update == null) throw new IllegalArgumentException("没有需要保存的模型设置");
        String effort = update.thinkingEffort() == null || update.thinkingEffort().isBlank()
                ? "AUTO" : update.thinkingEffort().strip().toUpperCase(Locale.ROOT);
        if (!THINKING_EFFORTS.contains(effort)) throw new IllegalArgumentException("无效的思考力度");
        Long window = update.contextWindowTokens();
        if (window != null && (window < MIN_CONTEXT_WINDOW || window > MAX_CONTEXT_WINDOW))
            throw new IllegalArgumentException("上下文窗口需在 32K 到 10M 之间");
        return new SettingsUpdate(update.modelChoice(), update.visible(), effort, window);
    }

    public record SettingsUpdate(String modelChoice, Boolean visible, String thinkingEffort,
            Long contextWindowTokens) {}

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

    private List<Option> relayOptions(UUID userId, AuxiliaryApiConfigService.AuxiliaryRuntimeConfig raw,
            Map<String, UserModelSettingsStore.Setting> settings) {
        List<String> models = relayModels(userId, raw);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (raw.model() != null && !raw.model().isBlank()) unique.add(raw.model());
        for (String model : models) {
            if (model != null && !model.isBlank()) unique.add(model);
            if (unique.size() >= MAX_RELAY_MODELS) break;
        }
        return unique.stream().map(model -> {
            var config = relayConfig(raw, model);
            return option(FALLBACK_PREFIX + model, config, settings);
        }).toList();
    }

    private List<String> relayModels(UUID userId, AuxiliaryApiConfigService.AuxiliaryRuntimeConfig raw) {
        LlmProvider configured = provider(raw.provider());
        String baseUrl = normalizedBaseUrl(raw.baseUrl());
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
        if (found.isEmpty()) {
            RelaySnapshot snapshot = relaySnapshots.get(userId);
            if (snapshot != null && snapshot.matches(configured.name(), baseUrl)) {
                // Relay discovery is a best-effort probe; a transient failure must not hide models
                // the user could still call successfully.
                log.info("Using last-known relay models: provider={}, host={}, count={}",
                        configured, host(baseUrl), snapshot.models().size());
                found.addAll(snapshot.models());
            }
        } else {
            if (relaySnapshots.size() >= MAX_CATALOG_CACHE_ENTRIES) relaySnapshots.clear();
            relaySnapshots.put(userId, new RelaySnapshot(configured.name(), baseUrl, List.copyOf(found)));
        }
        if (found.isEmpty() && raw.model() != null && !raw.model().isBlank()) found.add(raw.model());
        return new ArrayList<>(found);
    }

    private static String normalizedBaseUrl(String baseUrl) {
        return Optional.ofNullable(baseUrl).orElse("").replaceAll("/+$", "");
    }

    private record RelaySnapshot(String provider, String baseUrl, List<String> models) {
        boolean matches(String provider, String baseUrl) {
            return this.provider.equals(provider) && this.baseUrl.equals(baseUrl);
        }
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

    private Option option(String id, LlmConfigService.LlmRuntimeConfig config,
            Map<String, UserModelSettingsStore.Setting> settings) {
        String settingsKey = ModelConfigFingerprint.of(config);
        UserModelSettingsStore.Setting setting = settings.get(settingsKey);
        return new Option(id, config.model(), config.provider().name(),
                true, ModelReasoningPolicy.resolve(config, ReasoningDepth.AUTO).nativeControl(),
                ModelReasoningPolicy.levels(config), AgentImageModelPolicy.supports(config),
                setting == null || setting.visible(),
                setting == null ? "AUTO" : setting.thinkingEffort(),
                setting == null ? null : setting.contextWindowTokens(),
                settingsKey);
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
            List<ModelReasoningPolicy.Level> reasoningLevels, boolean imageInput, boolean visible,
            String thinkingEffort, Long contextWindowTokens, String settingsKey) {
        public Option(String id, String label, String provider, boolean available, boolean nativeControl,
                List<ModelReasoningPolicy.Level> reasoningLevels) {
            this(id, label, provider, available, nativeControl, reasoningLevels, false, true, "AUTO", null, null);
        }

        public Option(String id, String label, String provider, boolean available, boolean nativeControl,
                List<ModelReasoningPolicy.Level> reasoningLevels, boolean imageInput) {
            this(id, label, provider, available, nativeControl, reasoningLevels, imageInput, true, "AUTO", null, null);
        }
    }

    public record Resolved(String id, LlmConfigService.LlmRuntimeConfig config) {
    }

    private record CatalogEntry(List<Option> options, long createdAt) {}
    private record Prepared(SettingsUpdate update, LlmConfigService.LlmRuntimeConfig config) {}
}
