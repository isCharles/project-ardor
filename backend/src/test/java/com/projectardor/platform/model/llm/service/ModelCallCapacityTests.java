package com.projectardor.platform.model.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ModelCallCapacityTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void sharedSlotsRejectBeyondConfiguredConcurrency() {
        var capacity = new ModelCallCapacity(1, registry);

        assertThat(capacity.tryAcquire("agent")).isTrue();
        assertThat(capacity.tryAcquire("job")).isFalse();
        assertThat(registry.get("ardor.model.capacity.rejections").tag("surface", "job").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("ardor.model.capacity.in.use").gauge().value()).isEqualTo(1.0);

        capacity.release();
        assertThat(capacity.tryAcquire("job")).isTrue();
        capacity.release();
    }

    @Test
    void rateDenialReleasesTheProviderSlotAndCountsTheReason() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-2L);
        var capacity = new ModelCallCapacity(1, registry, new ModelRateWindow(0, 1_000, 0, redis));

        assertThat(capacity.tryAcquire("agent", UUID.randomUUID(), "model-a", 500)).isFalse();
        assertThat(registry.get("ardor.model.rate.rejections").tag("reason", "tpm").counter().count())
                .isEqualTo(1.0);
        assertThat(capacity.tryAcquire("agent")).isTrue();

        capacity.release();
    }

    @Test
    void degradedRateWindowAdmitsTheCallAndIsObservable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis down"));
        var capacity = new ModelCallCapacity(1, registry, new ModelRateWindow(60, 0, 0, redis));

        assertThat(capacity.tryAcquire("agent", UUID.randomUUID(), "model-a", 10)).isTrue();
        assertThat(registry.get("ardor.model.rate.degraded").counter().count()).isEqualTo(1.0);

        capacity.release();
    }

    @Test
    void busyFailureIsRetryableAndUsesTheSharedBusyCode() {
        var failure = new ModelCallCapacity(1, registry).busy();

        assertThat(failure.getCode()).isEqualTo("LLM_BUSY");
        assertThat(failure.isRetryable()).isTrue();
    }
}
