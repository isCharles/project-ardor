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
    private final Timer approvalWait;
    private final Timer toolBusiness;
    private final Timer contextPrepare;

    private final Counter compactions;
    private final Counter compactionFailures;
    private final Timer queueWait;
    private final Counter queueClaims;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.runDuration = Timer.builder("ardor.agent.run.duration")
                .description("Total wall-clock duration of an agent run")
                .publishPercentileHistogram()
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
                .publishPercentileHistogram()
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
                .publishPercentileHistogram()
                .register(registry);
        this.toolCallErrors = Counter.builder("ardor.agent.tool.call.errors")
                .description("Tool executions that failed")
                .register(registry);

        this.timeToFirstToken = Timer.builder("ardor.agent.stream.ttft")
                .description("Time from run start to first streamed token")
                .publishPercentileHistogram()
                .register(registry);
        this.approvalWait = Timer.builder("ardor.agent.tool.approval.wait")
                .description("Time a tool waited for user approval before execution or rejection")
                .publishPercentileHistogram()
                .register(registry);
        this.toolBusiness = Timer.builder("ardor.agent.tool.business.duration")
                .description("Time spent inside the business tool delegate, excluding approval wait")
                .publishPercentileHistogram()
                .register(registry);
        this.contextPrepare = Timer.builder("ardor.agent.context.prepare.duration")
                .description("Prompt assembly and optional context-summary time before the first model call")
                .publishPercentileHistogram()
                .register(registry);

        this.compactions = Counter.builder("ardor.agent.context.compactions")
                .description("Context summaries written for agent conversations")
                .register(registry);
        this.compactionFailures = Counter.builder("ardor.agent.context.compaction.failures")
                .description("Context summary attempts that produced no usable summary")
                .register(registry);

        this.queueWait = Timer.builder("ardor.agent.queue.wait")
                .description("Time a queued chat message waited before a worker claimed it")
                .publishPercentileHistogram()
                .register(registry);
        this.queueClaims = Counter.builder("ardor.agent.queue.claims")
                .description("Queued chat messages claimed by a worker")
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
                    .publishPercentileHistogram()
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
                    .publishPercentileHistogram()
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

    public void recordContextPrepare(long durationNanos) {
        try {
            contextPrepare.record(java.time.Duration.ofNanos(durationNanos));
        } catch (RuntimeException ignored) { }
    }

    public void recordApprovalWait(long durationNanos) {
        try {
            approvalWait.record(java.time.Duration.ofNanos(durationNanos));
        } catch (RuntimeException ignored) { }
    }

    public void recordToolBusiness(long durationNanos) {
        try {
            toolBusiness.record(java.time.Duration.ofNanos(durationNanos));
        } catch (RuntimeException ignored) { }
    }

    public void recordQueueClaim(long waitNanos) {
        try {
            queueWait.record(java.time.Duration.ofNanos(Math.max(0, waitNanos)));
            queueClaims.increment();
        } catch (RuntimeException ignored) { }
    }

    public void recordQueueFailure(String reason) {
        try {
            Counter.builder("ardor.agent.queue.failures")
                    .description("Queued chat messages that never started or ended interrupted")
                    .tag("reason", reason == null ? "unknown" : reason)
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) { }
    }

    public void recordJobClaim(String jobType, long waitNanos) {
        try {
            Timer.builder("ardor.agent.job.wait")
                    .description("Time an AI job stayed QUEUED before a worker claimed it")
                    .tag("type", jobType)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(java.time.Duration.ofNanos(Math.max(0, waitNanos)));
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
