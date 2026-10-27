package com.projectardor.assistant.execution.service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Service;

/**
 * Centralised Micrometer instrumentation for Agent runs.
 *
 * <p>All metrics follow the naming convention {@code ardor.agent.<domain>.<metric>}.
 * Metrics are fire-and-forget: a missing registry or recording failure never breaks a run.</p>
 */
@Service
public final class AgentMetrics {

    private final MeterRegistry registry;

    private final Timer runDuration;
    private final Counter runSuccesses;
    private final Counter runErrors;
    private final AtomicInteger activeRuns;

    private final Timer llmCallDuration;
    private final Counter llmInputTokens;
    private final Counter llmOutputTokens;
    private final Counter llmErrors;

    private final Timer toolCallDuration;
    private final Counter toolCallErrors;

    private final Timer timeToFirstToken;

    private final Counter compactions;
    private final Counter compactionFailures;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.runDuration = Timer.builder("ardor.agent.run.duration")
                .description("Total wall-clock duration of an agent run")
                .register(registry);
        this.runSuccesses = Counter.builder("ardor.agent.run.successes")
                .description("Agent runs that completed successfully")
                .register(registry);
        this.runErrors = Counter.builder("ardor.agent.run.errors")
                .description("Agent runs that ended in error")
                .register(registry);
        this.activeRuns = registry.gauge("ardor.agent.run.active", new AtomicInteger(0));

        this.llmCallDuration = Timer.builder("ardor.agent.llm.call.duration")
                .description("Duration of each LLM call within an agent run")
                .register(registry);
        this.llmInputTokens = Counter.builder("ardor.agent.llm.tokens.input")
                .description("Total input tokens consumed by LLM calls")
                .register(registry);
        this.llmOutputTokens = Counter.builder("ardor.agent.llm.tokens.output")
                .description("Total output tokens produced by LLM calls")
                .register(registry);
        this.llmErrors = Counter.builder("ardor.agent.llm.errors")
                .description("LLM calls that ended in error")
                .register(registry);

        this.toolCallDuration = Timer.builder("ardor.agent.tool.call.duration")
                .description("Duration of each tool execution within an agent run")
                .register(registry);
        this.toolCallErrors = Counter.builder("ardor.agent.tool.call.errors")
                .description("Tool executions that failed")
                .register(registry);

        this.timeToFirstToken = Timer.builder("ardor.agent.stream.ttft")
                .description("Time from run start to first streamed token")
                .register(registry);

        this.compactions = Counter.builder("ardor.agent.context.compactions")
                .description("Context summaries written for agent conversations")
                .register(registry);
        this.compactionFailures = Counter.builder("ardor.agent.context.compaction.failures")
                .description("Context summary attempts that produced no usable summary")
                .register(registry);
    }

    public void recordRunStart() {
        try { activeRuns.incrementAndGet(); } catch (RuntimeException ignored) { }
    }

    public void recordRunEnd(long durationNanos, boolean success, String errorCode) {
        try {
            activeRuns.decrementAndGet();
            runDuration.record(java.time.Duration.ofNanos(durationNanos));
            if (success) {
                runSuccesses.increment();
            } else {
                runErrors.increment();
            }
        } catch (RuntimeException ignored) { }
    }

    public void recordLlmCall(long durationNanos, Integer inputTokens, Integer outputTokens,
            String model, boolean error) {
        try {
            Timer llmByModel = Timer.builder("ardor.agent.llm.call.duration")
                    .tag("model", model == null ? "unknown" : model)
                    .register(registry);
            llmByModel.record(java.time.Duration.ofNanos(durationNanos));
            if (inputTokens != null && inputTokens > 0) {
                Counter.builder("ardor.agent.llm.tokens.input")
                        .tag("model", model == null ? "unknown" : model)
                        .register(registry)
                        .increment(inputTokens);
            }
            if (outputTokens != null && outputTokens > 0) {
                Counter.builder("ardor.agent.llm.tokens.output")
                        .tag("model", model == null ? "unknown" : model)
                        .register(registry)
                        .increment(outputTokens);
            }
            if (error) {
                Counter.builder("ardor.agent.llm.errors")
                        .tag("model", model == null ? "unknown" : model)
                        .register(registry)
                        .increment();
            }
        } catch (RuntimeException ignored) { }
    }

    public void recordToolCall(long durationNanos, String toolName, boolean error) {
        try {
            Timer toolByName = Timer.builder("ardor.agent.tool.call.duration")
                    .tag("tool", toolName == null ? "unknown" : toolName)
                    .register(registry);
            toolByName.record(java.time.Duration.ofNanos(durationNanos));
            if (error) {
                Counter.builder("ardor.agent.tool.call.errors")
                        .tag("tool", toolName == null ? "unknown" : toolName)
                        .register(registry)
                        .increment();
            }
        } catch (RuntimeException ignored) { }
    }

    public void recordTimeToFirstToken(long durationNanos) {
        try {
            timeToFirstToken.record(java.time.Duration.ofNanos(durationNanos));
        } catch (RuntimeException ignored) { }
    }

    public void recordCompaction(boolean failed) {
        try {
            (failed ? compactionFailures : compactions).increment();
        } catch (RuntimeException ignored) { }
    }

    public void recordJobCompletion(String jobType, String status) {
        try {
            Counter.builder("ardor.agent.job.completions")
                    .tag("type", jobType)
                    .tag("status", status)
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) { }
    }

    public void recordJobRetry(String jobType) {
        try {
            Counter.builder("ardor.agent.job.retries")
                    .tag("type", jobType)
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) { }
    }

    public void recordJobDeadLetter(String jobType) {
        try {
            Counter.builder("ardor.agent.job.dead_letters")
                    .tag("type", jobType)
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) { }
    }
}
