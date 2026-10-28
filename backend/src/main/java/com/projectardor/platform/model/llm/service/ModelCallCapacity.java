package com.projectardor.platform.model.llm.service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Shared concurrency guard for model transports.
 *
 * <p>Online chats and background jobs use different workers but the same provider account, so a
 * single semaphore keeps their combined in-flight calls bounded. A separately configured Redis
 * window can also bound requests and estimated tokens per minute across instances. Rejection is an
 * {@code LLM_BUSY} retryable failure: the browser shows it and background jobs requeue with
 * exponential backoff.</p>
 */
@Component
public class ModelCallCapacity {
    private final Semaphore slots;
    private final MeterRegistry registry;
    private final int maxConcurrent;
    private final ModelRateWindow rateWindow;

    @org.springframework.beans.factory.annotation.Autowired
    public ModelCallCapacity(@Value("${app.model.max-concurrent-calls:8}") int maxConcurrent,
            MeterRegistry registry, ModelRateWindow rateWindow) {
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
        this(maxConcurrent, registry, ModelRateWindow.disabled());
    }

    public boolean tryAcquire(String surface) {
        if (slots.tryAcquire()) return true;
        rejectBusy(surface);
        return false;
    }

    public boolean tryAcquire(String surface, UUID userId, String modelKey, long estimatedTokens) {
        if (!slots.tryAcquire()) {
            rejectBusy(surface);
            return false;
        }
        if (!rateWindow.enabled()) return true;
        var decision = rateWindow.admit(userId, modelKey, estimatedTokens, Instant.now());
        if (decision == ModelRateWindow.Decision.DEGRADED) {
            count("ardor.model.rate.degraded", null);
            return true;
        }
        if (decision == ModelRateWindow.Decision.ALLOWED) return true;
        slots.release();
        count("ardor.model.rate.rejections", reason(decision));
        return false;
    }

    public void release() {
        slots.release();
    }

    public LlmCallException busy() {
        return new LlmCallException("LLM_BUSY", "当前模型请求较多，请稍后重试", true, null);
    }

    private void rejectBusy(String surface) {
        try {
            Counter.builder("ardor.model.capacity.rejections")
                    .description("Model calls rejected because every shared provider slot was busy")
                    .tag("surface", surface)
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) { }
    }

    private void count(String name, String reason) {
        try {
            Counter.Builder builder = Counter.builder(name)
                    .description("Model calls admitted despite a degraded rate limiter or rejected by it");
            if (reason != null) builder.tag("reason", reason);
            builder.register(registry).increment();
        } catch (RuntimeException ignored) { }
    }

    private static String reason(ModelRateWindow.Decision decision) {
        return switch (decision) {
            case QPM_LIMIT -> "qpm";
            case TPM_LIMIT -> "tpm";
            case USER_QPM_LIMIT -> "user-qpm";
            default -> "unknown";
        };
    }
}
