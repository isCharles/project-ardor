package com.projectardor.assistant.execution.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService.LlmRuntimeConfig;

/** Conservative native mapping; unknown models still receive the depth's prompt guidance. */
public final class ModelReasoningPolicy {
    private ModelReasoningPolicy() {}

    public record Profile(String effort, Map<String, Object> extra, String thinkingType,
            Integer budget, int maxTokens, boolean sendThinking, boolean nativeControl) {}

    public record Level(String value, String label) {}

    /**
     * Relay /models payloads do not expose a standardized reasoning-level schema, so levels are
     * inferred from the model family and protocol. The composer reads this list instead of
     * hard-coding one wide slider for every model.
     */
    public static List<Level> levels(LlmRuntimeConfig config) {
        String model = config.model().toLowerCase(Locale.ROOT);
        if (!resolve(config, ReasoningDepth.QUICK).nativeControl()) {
            return List.of(new Level("QUICK", "Low"), new Level("STANDARD", "High"), new Level("DEEP", "Max"));
        }
        if (model.contains("deepseek")) {
            return List.of(new Level("QUICK", "Low"), new Level("STANDARD", "High"), new Level("DEEP", "Max"));
        }
        return List.of(new Level("QUICK", "Low"), new Level("STANDARD", "Medium"), new Level("DEEP", "High"));
    }

    public static Profile resolve(LlmRuntimeConfig config, ReasoningDepth requested) {
        ReasoningDepth depth = ReasoningDepth.orAuto(requested);
        String model = config.model().toLowerCase(Locale.ROOT);
        boolean deepseek = model.contains("deepseek");
        boolean openai = model.matches(".*(?:gpt-[56](?:[.-].*)?|o[134](?:-.*)?)")
                && !model.contains("o1-mini") && !model.contains("o1-preview");
        boolean claude = model.contains("claude") && (model.contains("-4") || model.contains("-5"));
        boolean nativeControl = deepseek || (config.provider() == LlmProvider.OPENAI_COMPATIBLE && openai)
                || (config.provider() == LlmProvider.ANTHROPIC_COMPATIBLE && claude);
        if (depth == ReasoningDepth.AUTO) return new Profile(null, Map.of(), null, null, 4096,
                deepseek || (config.provider() == LlmProvider.ANTHROPIC_COMPATIBLE && claude), nativeControl);
        String effort = switch (depth) { case QUICK -> "low"; case STANDARD -> "medium"; default -> "high"; };
        if (deepseek) {
            String mapped = switch (depth) { case QUICK -> "low"; case STANDARD -> "high"; default -> "max"; };
            return config.provider() == LlmProvider.OPENAI_COMPATIBLE
                    ? new Profile(mapped, Map.of("thinking", Map.of("type", "enabled")), null, null, 4096, true, true)
                    : new Profile(null, Map.of("output_config", Map.of("effort", mapped)), "enabled", null, 4096, true, true);
        }
        if (config.provider() == LlmProvider.OPENAI_COMPATIBLE && openai)
            return new Profile(effort, Map.of(), null, null, 4096, false, true);
        if (config.provider() == LlmProvider.ANTHROPIC_COMPATIBLE && claude) {
            boolean adaptive = model.matches(".*claude-(?:[a-z]+-)?4[.-](?:[6-9]|[1-9][0-9]+)(?:[.-].*)?")
                    || model.matches(".*claude-(?:[a-z]+-)?5(?:[.-].*)?");
            if (adaptive) return new Profile(null, Map.of("output_config", Map.of("effort", effort)),
                    "adaptive", null, 16384, true, true);
            int budget = switch (depth) { case QUICK -> 1024; case STANDARD -> 4096; default -> 8192; };
            return new Profile(null, Map.of(), "enabled", budget, budget + 4096, true, true);
        }
        return new Profile(null, Map.of(), null, null, 4096, false, false);
    }
}
