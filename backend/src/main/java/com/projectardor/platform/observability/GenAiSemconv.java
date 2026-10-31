package com.projectardor.platform.observability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;

/**
 * Technical attributes exposed to an OTLP backend.
 *
 * <p>Pinned to OpenTelemetry semantic conventions 1.41.1, which is the version shipped by
 * Spring Boot 4.1.1. The GenAI conventions are still development-stage and live outside the
 * stable convention release; only the attributes used here are relied on. Ardor-specific fields
 * use the {@code ardor.} namespace.</p>
 *
 * <p>This mapper is an allowlist on purpose. The product trace keeps bounded input/output
 * previews, while the technical backend must never receive prompts, tool arguments, tool results
 * or credentials. Unknown attribute keys are dropped even if their values look small.</p>
 */
final class GenAiSemconv {

    static final String CONVENTION_VERSION = "1.41.1";

    static final String OPERATION_NAME = "gen_ai.operation.name";
    static final String PROVIDER_NAME = "gen_ai.provider.name";
    static final String REQUEST_MODEL = "gen_ai.request.model";
    static final String RESPONSE_MODEL = "gen_ai.response.model";
    static final String INPUT_TOKENS = "gen_ai.usage.input_tokens";
    static final String OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    static final String CACHE_READ_TOKENS = "gen_ai.usage.cache_read.input_tokens";
    static final String CACHE_CREATION_TOKENS = "gen_ai.usage.cache_creation.input_tokens";
    static final String FINISH_REASONS = "gen_ai.response.finish_reasons";
    static final String TOOL_NAME = "gen_ai.tool.name";
    static final String TOOL_CALL_ID = "gen_ai.tool.call.id";
    static final String AGENT_NAME = "gen_ai.agent.name";
    static final String ERROR_TYPE = "error.type";

    static final String SPAN_KIND = "ardor.span.kind";
    static final String SPAN_STATUS = "ardor.span.status";
    static final String RUN_ID = "ardor.run.id";
    static final String CONVERSATION_ID = "ardor.conversation.id";
    static final String ORIGIN_RUN_ID = "ardor.origin_run.id";
    static final String JOB_ID = "ardor.job.id";
    static final String PROMPT_VERSION = "ardor.prompt.version";
    static final String LLM_CALL_INDEX = "ardor.llm.call.index";
    static final String LLM_PURPOSE = "ardor.llm.purpose";
    static final String LLM_MESSAGE_COUNT = "ardor.llm.message_count";
    static final String LLM_TOOL_ROUND = "ardor.llm.tool_round";
    static final String USAGE_MISSING = "ardor.usage.missing";
    static final String CACHE_USAGE_MISSING = "ardor.cache_usage.missing";
    static final String REQUESTED_TOOLS = "ardor.agent.requested_tools";
    static final String TOOL_EXECUTED = "ardor.tool.executed";
    static final String TOOL_PROPOSAL = "ardor.tool.proposal";
    static final String PROVIDER_PROTOCOL = "ardor.provider.protocol";
    static final String QUEUE_WAIT_MS = "ardor.queue.wait_ms";
    static final String QUEUE_ID = "ardor.queue.id";
    static final String JOB_TYPE = "ardor.job.type";
    static final String JOB_ATTEMPT = "ardor.job.attempt";

    private static final int MAX_VALUE_LENGTH = 256;
    private static final Pattern SAFE_ID = Pattern.compile("[0-9a-fA-F-]{1,64}");

    private static final Map<String, String> DIRECT_NUMERIC = numericKeys();
    private static final Map<String, String> DIRECT_BOOLEAN = booleanKeys();
    private static final Map<String, String> DIRECT_STRING = stringKeys();

    private GenAiSemconv() {
    }

    /** Root attributes for one Agent run, attached in addition to the business span metadata. */
    static Attributes runAttributes(UUID runId, UUID conversationId, String model, String provider,
            String promptVersion, Map<String, Object> businessAttributes) {
        AttributesBuilder attributes = Attributes.builder();
        attributes.put(OPERATION_NAME, "invoke_agent");
        attributes.put(AGENT_NAME, "ardor");
        attributes.put(SPAN_KIND, "RUN");
        putSafe(attributes, RUN_ID, runId == null ? null : runId.toString());
        putSafe(attributes, CONVERSATION_ID, conversationId == null ? null : conversationId.toString());
        putSafe(attributes, REQUEST_MODEL, model);
        putSafe(attributes, PROVIDER_NAME, normalizeProvider(provider));
        putSafe(attributes, PROVIDER_PROTOCOL, provider);
        putSafe(attributes, PROMPT_VERSION, promptVersion);
        if (businessAttributes != null) {
            putSafe(attributes, ORIGIN_RUN_ID, idValue(businessAttributes.get("originRun")));
            putSafe(attributes, JOB_ID, idValue(businessAttributes.get("jobId")));
        }
        return attributes.build();
    }

    /** Attributes known when a span starts. Only allowlisted metadata crosses the boundary. */
    static Attributes spanAttributes(String kind, String model, String provider, Map<String, Object> business) {
        AttributesBuilder attributes = Attributes.builder();
        putSafe(attributes, SPAN_KIND, kind);
        switch (kind) {
            case "LLM", "COMPACTION" -> {
                attributes.put(OPERATION_NAME, "chat");
                putSafe(attributes, LLM_PURPOSE, "COMPACTION".equals(kind) ? "CONTEXT_COMPACTION"
                        : stringValue(business, "purpose"));
            }
            case "TOOL" -> attributes.put(OPERATION_NAME, "execute_tool");
            default -> {
                // CONTEXT, PLAN and PLAN_STEP are product concepts without a GenAI operation name.
            }
        }
        putSafe(attributes, REQUEST_MODEL, model);
        String effectiveProvider = provider != null ? provider : stringValue(business, "provider");
        putSafe(attributes, PROVIDER_NAME, normalizeProvider(effectiveProvider));
        putSafe(attributes, PROVIDER_PROTOCOL, effectiveProvider);
        putSafe(attributes, TOOL_NAME, stringValue(business, "tool"));
        putSafe(attributes, TOOL_CALL_ID, stringValue(business, "toolCallId"));
        applyAllowlistedBusiness(attributes, business);
        return attributes.build();
    }

    /**
     * Completion-only facts such as timings and requested tools use the same allowlist. The
     * product trace already bounds these fields; previews are never part of the allowlist.
     */
    private static void applyAllowlistedBusiness(AttributesBuilder attributes, Map<String, Object> business) {
        if (business == null) return;
        for (Map.Entry<String, String> mapping : DIRECT_STRING.entrySet()) {
            putSafe(attributes, mapping.getValue(), stringValue(business, mapping.getKey()));
        }
        for (Map.Entry<String, String> mapping : DIRECT_NUMERIC.entrySet()) {
            Object value = business.get(mapping.getKey());
            if (value instanceof Number number) attributes.put(AttributeKey.longKey(mapping.getValue()), number.longValue());
        }
        for (Map.Entry<String, String> mapping : DIRECT_BOOLEAN.entrySet()) {
            Object value = business.get(mapping.getKey());
            if (value instanceof Boolean flag) attributes.put(AttributeKey.booleanKey(mapping.getValue()), flag);
        }
        if (business.get("requestedTools") instanceof List<?> requested) {
            List<String> names = requested.stream().filter(String.class::isInstance).map(String.class::cast)
                    .map(GenAiSemconv::safe).filter(value -> !value.isEmpty()).toList();
            if (!names.isEmpty()) attributes.put(AttributeKey.stringArrayKey(REQUESTED_TOOLS), names);
        }
        if (business.get("tools") instanceof List<?> tools) {
            List<String> names = tools.stream().filter(String.class::isInstance).map(String.class::cast)
                    .map(GenAiSemconv::safe).filter(value -> !value.isEmpty()).toList();
            if (!names.isEmpty()) attributes.put(AttributeKey.stringArrayKey(REQUESTED_TOOLS), names);
        }
    }

    /** Attributes added when a span finishes: model, usage, status and the finish reason. */
    static Attributes finishAttributes(String status, String responseModel, Integer inputTokens, Integer outputTokens,
            Integer cacheCreation, Integer cacheRead, Map<String, Object> business) {
        AttributesBuilder attributes = Attributes.builder();
        putSafe(attributes, SPAN_STATUS, status);
        putSafe(attributes, RESPONSE_MODEL, responseModel);
        if (inputTokens != null) attributes.put(INPUT_TOKENS, Math.max(0, inputTokens));
        if (outputTokens != null) attributes.put(OUTPUT_TOKENS, Math.max(0, outputTokens));
        if (cacheCreation != null) attributes.put(CACHE_CREATION_TOKENS, Math.max(0, cacheCreation));
        if (cacheRead != null) attributes.put(CACHE_READ_TOKENS, Math.max(0, cacheRead));
        if (business != null) {
            String finishReason = stringValue(business, "finishReason");
            if (finishReason != null && !finishReason.isBlank()) {
                attributes.put(AttributeKey.stringArrayKey(FINISH_REASONS), List.of(safe(finishReason)));
            }
            if (business.get("executed") instanceof Boolean executed) {
                attributes.put(AttributeKey.booleanKey(TOOL_EXECUTED), executed);
            }
            if (business.get("proposal") instanceof Boolean proposal) {
                attributes.put(AttributeKey.booleanKey(TOOL_PROPOSAL), proposal);
            }
            if (business.get("toolRound") instanceof Boolean round) {
                attributes.put(AttributeKey.booleanKey(LLM_TOOL_ROUND), round);
            }
            if (business.get("usageMissing") instanceof Boolean missing) {
                attributes.put(AttributeKey.booleanKey(USAGE_MISSING), missing);
            }
            if (business.get("cacheUsageMissing") instanceof Boolean missing) {
                attributes.put(AttributeKey.booleanKey(CACHE_USAGE_MISSING), missing);
            }
        }
        applyAllowlistedBusiness(attributes, business);
        return attributes.build();
    }

    /** Late annotations are allowed only for the same reviewed fields. */
    static Attributes annotationAttributes(Map<String, Object> business) {
        if (business == null || business.isEmpty()) return Attributes.empty();
        AttributesBuilder attributes = Attributes.builder();
        for (Map.Entry<String, String> mapping : DIRECT_STRING.entrySet()) {
            putSafe(attributes, mapping.getValue(), stringValue(business, mapping.getKey()));
        }
        for (Map.Entry<String, String> mapping : DIRECT_NUMERIC.entrySet()) {
            Object value = business.get(mapping.getKey());
            if (value instanceof Number number) attributes.put(AttributeKey.longKey(mapping.getValue()), number.longValue());
        }
        for (Map.Entry<String, String> mapping : DIRECT_BOOLEAN.entrySet()) {
            Object value = business.get(mapping.getKey());
            if (value instanceof Boolean flag) attributes.put(AttributeKey.booleanKey(mapping.getValue()), flag);
        }
        return attributes.build();
    }

    static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) return null;
        String normalized = provider.toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.startsWith("open-ai") || normalized.equals("openai") || normalized.contains("openai")) return "openai";
        if (normalized.contains("anthropic")) return "anthropic";
        return safe(normalized);
    }

    private static Map<String, String> numericKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("call", LLM_CALL_INDEX);
        keys.put("messages", LLM_MESSAGE_COUNT);
        keys.put("historyLimit", "ardor.context.history_limit");
        keys.put("promptChars", "ardor.context.prompt_chars");
        keys.put("residentMemoryCandidates", "ardor.context.memory_candidates");
        keys.put("residentMemoryAdopted", "ardor.context.memory_adopted");
        keys.put("residentMemoryOmitted", "ardor.context.memory_omitted");
        keys.put("residentMemoryChars", "ardor.context.memory_chars");
        keys.put("residentMemoryEmbeddingCalls", "ardor.context.memory_embedding_calls");
        keys.put("historyMessages", "ardor.context.history_messages");
        keys.put("interruptedMessages", "ardor.context.interrupted_messages");
        keys.put("estimatedTokens", "ardor.context.estimated_tokens");
        keys.put("contextBudgetTokens", "ardor.context.budget_tokens");
        keys.put("hardInputBudgetTokens", "ardor.context.hard_input_tokens");
        keys.put("outputReserveTokens", "ardor.context.output_reserve_tokens");
        keys.put("safetyReserveTokens", "ardor.context.safety_reserve_tokens");
        keys.put("assembledEstimatedTokens", "ardor.context.assembled_tokens");
        keys.put("compactedMessages", "ardor.context.compacted_messages");
        keys.put("summaryVersion", "ardor.context.summary_version");
        keys.put("compactionThresholdTokens", "ardor.context.compaction_threshold_tokens");
        keys.put("compactionProjectedInputTokens", "ardor.context.compaction_projected_input_tokens");
        keys.put("compactionEstimatedInputTokensAfter", "ardor.context.compaction_input_tokens_after");
        keys.put("compactionSummarizedMessages", "ardor.context.compaction_summarized_messages");
        keys.put("revision", "ardor.plan.revision");
        keys.put("stepCount", "ardor.plan.step_count");
        keys.put("verifiedSteps", "ardor.plan.verified_steps");
        keys.put("stepIndex", "ardor.plan.step_index");
        keys.put("requestDurationMs", "ardor.llm.request_duration_ms");
        keys.put("firstContentMs", "ardor.llm.first_content_ms");
        keys.put("streamAfterFirstContentMs", "ardor.llm.stream_after_first_content_ms");
        keys.put("outputTokensPerRequestSecond", "ardor.llm.output_tokens_per_second");
        return Map.copyOf(keys);
    }

    private static Map<String, String> booleanKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("overContextBudget", "ardor.context.over_budget");
        keys.put("verified", "ardor.plan.step_verified");
        keys.put("retryBlocked", "ardor.tool.retry_blocked");
        return Map.copyOf(keys);
    }

    private static Map<String, String> stringKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("purpose", LLM_PURPOSE);
        keys.put("residentMemoryOutcome", "ardor.context.memory_outcome");
        keys.put("compactionOutcome", "ardor.context.compaction_outcome");
        keys.put("compactionSpanId", "ardor.context.compaction_span_id");
        keys.put("compactionFailureCode", "ardor.context.compaction_failure_code");
        keys.put("timingSource", "ardor.llm.timing_source");
        keys.put("planId", "ardor.plan.id");
        return Map.copyOf(keys);
    }

    private static String stringValue(Map<String, Object> values, String key) {
        if (values == null) return null;
        Object value = values.get(key);
        if (value instanceof String text) return safe(text);
        if (value instanceof Enum<?> enumValue) return safe(enumValue.name());
        if (value instanceof UUID id) return id.toString();
        return null;
    }

    private static String idValue(Object value) {
        if (value instanceof UUID id) return id.toString();
        if (value instanceof String text && !text.isBlank() && SAFE_ID.matcher(text).matches()) return text;
        return null;
    }

    private static void putSafe(AttributesBuilder attributes, String key, String value) {
        String safe = safe(value);
        if (!safe.isEmpty()) attributes.put(key, safe);
    }

    private static String safe(String value) {
        if (value == null) return "";
        String trimmed = value.strip();
        if (trimmed.isEmpty()) return "";
        return trimmed.length() <= MAX_VALUE_LENGTH ? trimmed : trimmed.substring(0, MAX_VALUE_LENGTH);
    }
}
