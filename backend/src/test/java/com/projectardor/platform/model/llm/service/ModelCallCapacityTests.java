package com.projectardor.platform.model.llm.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
    void busyFailureIsRetryableAndUsesTheSharedBusyCode() {
        var failure = new ModelCallCapacity(1, registry).busy();

        assertThat(failure.getCode()).isEqualTo("LLM_BUSY");
        assertThat(failure.isRetryable()).isTrue();
    }
}
