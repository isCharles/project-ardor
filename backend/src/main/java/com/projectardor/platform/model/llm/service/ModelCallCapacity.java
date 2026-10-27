package com.projectardor.platform.model.llm.service;

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
 * single semaphore keeps their combined in-flight calls bounded. Rejection is an {@code LLM_BUSY}
 * retryable failure: the browser shows it and background jobs requeue with exponential backoff.</p>
 */
@Component
public class ModelCallCapacity {
    private final Semaphore slots;
    private final MeterRegistry registry;
    private final int maxConcurrent;

    @org.springframework.beans.factory.annotation.Autowired
    public ModelCallCapacity(@Value("${app.model.max-concurrent-calls:8}") int maxConcurrent,
            MeterRegistry registry) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.slots = new Semaphore(this.maxConcurrent);
        this.registry = registry;
        Gauge.builder("ardor.model.capacity.in.use", slots,
                semaphore -> (double) (this.maxConcurrent - semaphore.availablePermits()))
                .description("Model calls currently holding a shared provider slot")
                .register(registry);
    }

    /** Used by non-Spring constructors in tests and compatibility paths. */
    public ModelCallCapacity(int maxConcurrent) {
        this(maxConcurrent, new SimpleMeterRegistry());
    }

    public boolean tryAcquire(String surface) {
        if (slots.tryAcquire()) return true;
        try {
            Counter.builder("ardor.model.capacity.rejections")
                    .description("Model calls rejected because every shared provider slot was busy")
                    .tag("surface", surface)
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) { }
        return false;
    }

    public void release() {
        slots.release();
    }

    public LlmCallException busy() {
        return new LlmCallException("LLM_BUSY", "当前模型并发已满，请稍后重试", true, null);
    }
}
