package com.projectardor.platform.model.llm.service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Shared admission guard for model transports.
 *
 * <p>Two independent bounds, checked in that order: the provider rate window, then a process-local
 * count of in-flight calls. Rate first because a permit held while a provider budget drains would keep
 * online chats out of the few slots a background job is already using; a call refused for local
 * capacity gives its rate charge back, so a rejection here never spends provider budget.</p>
 *
 * <p>The semaphore bounds outbound calls in this JVM. It is not a cross-instance guarantee: several
 * replicas each keep their own permits, so only the Redis rate window is shared. Rejection is a
 * retryable {@code LLM_BUSY} failure with a {@code Retry-After}: the browser shows it and background
 * jobs requeue with exponential backoff.</p>
 */
@Component
public class ModelCallCapacity {
    private static final int CAPACITY_RETRY_AFTER_SECONDS = 1;

    private final Semaphore slots;
    private final MeterRegistry registry;
    private final int maxConcurrent;
    private final SlidingRateWindow rateWindow;

    /**
     * @param retryAfterSeconds how long the caller should wait; known for rate denials, guessed for a
     *                          busy process or an unreachable guard
     * @param guardUnavailable  true when no decision could be made at all, which the API reports as a
     *                          service outage rather than as traffic
     */
    public record Admission(boolean acquired, int retryAfterSeconds, String reason, boolean guardUnavailable) {
        private static final Admission ACQUIRED = new Admission(true, 0, null, false);

        private static Admission denied(String reason, int retryAfterSeconds) {
            return new Admission(false, retryAfterSeconds, reason, false);
        }

        private static Admission unavailable(String reason) {
            return new Admission(false, CAPACITY_RETRY_AFTER_SECONDS, reason, true);
        }
    }

    @Autowired
    public ModelCallCapacity(@Value("${app.model.max-concurrent-calls:8}") int maxConcurrent,
            MeterRegistry registry, SlidingRateWindow rateWindow) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.slots = new Semaphore(this.maxConcurrent);
        this.registry = registry;
        this.rateWindow = rateWindow;
        Gauge.builder("ardor.model.capacity.in.use", slots,
                semaphore -> (double) (this.maxConcurrent - semaphore.availablePermits()))
                .description("Model calls currently holding a shared provider slot")
                .register(registry);
    }

    /** Used by non-Spring constructors in tests and compatibility paths. */
    public ModelCallCapacity(int maxConcurrent) {
        this(maxConcurrent, new SimpleMeterRegistry());
    }

    public ModelCallCapacity(int maxConcurrent, MeterRegistry registry) {
        this(maxConcurrent, registry, SlidingRateWindow.disabled());
    }

    public boolean tryAcquire(String surface) {
        return acquireSlot(surface).acquired();
    }

    public Admission tryAcquire(String surface, UUID userId, ModelRateGroup group, long estimatedTokens) {
        var permit = rateWindow.admit(userId, group, estimatedTokens, Instant.now());
        if (permit.decision() == SlidingRateWindow.Decision.UNAVAILABLE) {
            count("ardor.model.rate.rejections", permit.dimension());
            return Admission.unavailable("guard-unavailable");
        }
        if (!permit.allowed()) {
            count("ardor.model.rate.rejections", permit.dimension());
            return Admission.denied(permit.dimension(), permit.retryAfterSeconds());
        }
        Admission acquired = acquireSlot(surface);
        if (!acquired.acquired()) rateWindow.refund(permit, Instant.now());
        return acquired;
    }

    /** Release the local in-flight permit taken by a successful {@code tryAcquire}. */
    public void release() {
        slots.release();
    }

    public LlmCallException busy() {
        return busy(Admission.denied("capacity", CAPACITY_RETRY_AFTER_SECONDS));
    }

    public LlmCallException busy(Admission admission) {
        if (admission.guardUnavailable()) {
            return new LlmCallException("LLM_GUARD_UNAVAILABLE", "模型限流服务暂时无法确认，请稍后重试", true,
                    admission.retryAfterSeconds(), null);
        }
        return new LlmCallException("LLM_BUSY", "当前模型请求较多，请稍后重试", true,
                admission.retryAfterSeconds(), null);
    }

    private Admission acquireSlot(String surface) {
        if (slots.tryAcquire()) return Admission.ACQUIRED;
        try {
            Counter.builder("ardor.model.capacity.rejections")
                    .description("Model calls rejected because every shared provider slot was busy")
                    .tag("surface", surface == null ? "unknown" : surface)
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) { }
        return Admission.denied("capacity", CAPACITY_RETRY_AFTER_SECONDS);
    }

    private void count(String name, String reason) {
        try {
            Counter.builder(name).description("Model calls rejected by the shared provider rate window")
                    .tag("reason", reason == null ? "unknown" : reason)
                    .register(registry).increment();
        } catch (RuntimeException ignored) { }
    }
}
