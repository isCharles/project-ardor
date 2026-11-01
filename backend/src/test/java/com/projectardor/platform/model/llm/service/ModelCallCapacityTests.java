package com.projectardor.platform.model.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.platform.model.llm.domain.LlmProvider;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ModelCallCapacityTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UUID user = UUID.randomUUID();
    private final ModelRateGroup group = ModelRateGroup.of(LlmProvider.OPENAI_COMPATIBLE,
            "https://api.example.invalid", "synthetic-key", "synthetic-model");

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
    void rateDenialNamesItsDimensionAndSaysHowLongToWait() {
        var window = mock(SlidingRateWindow.class);
        var bucket = new SlidingRateWindow.Bucket("ardor:model:rate:{a}:requests", "account-qpm", 60_000, 1);
        when(window.admit(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new SlidingRateWindow.Outcome(SlidingRateWindow.Decision.LIMITED, 2_500,
                        "account-qpm", List.of()));
        var capacity = new ModelCallCapacity(1, registry, window);

        var admission = capacity.tryAcquire("agent", user, group, 500);

        assertThat(admission.acquired()).isFalse();
        assertThat(admission.reason()).isEqualTo("account-qpm");
        assertThat(admission.retryAfterSeconds()).isEqualTo(3);
        assertThat(registry.get("ardor.model.rate.rejections").tag("reason", "account-qpm").counter().count())
                .isEqualTo(1.0);
        // A denial charged nothing, so the slot stays free for the call that is admitted next.
        assertThat(capacity.tryAcquire("agent")).isTrue();
        assertThat(capacity.busy(admission).getCode()).isEqualTo("LLM_BUSY");
        assertThat(capacity.busy(admission).isRetryable()).isTrue();
        assertThat(capacity.busy(admission).getRetryAfterSeconds()).isEqualTo(3);

        capacity.release();
    }

    @Test
    void aBusyProcessGivesBackTheRateChargeItNeverUsed() {
        var window = mock(SlidingRateWindow.class);
        var charged = new SlidingRateWindow.Bucket("ardor:model:rate:{a}:tokens", "account-tpm", 60_000, 500);
        var admitted = new SlidingRateWindow.Outcome(SlidingRateWindow.Decision.ALLOWED, 0, null, List.of(charged));
        when(window.admit(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(admitted);
        var capacity = new ModelCallCapacity(1, registry, window);

        assertThat(capacity.tryAcquire("agent", user, group, 500).acquired()).isTrue();
        // The only slot is now held, so the next call is refused for capacity — after the budget was paid.
        var second = capacity.tryAcquire("job", user, group, 500);

        assertThat(second.acquired()).isFalse();
        assertThat(second.reason()).isEqualTo("capacity");
        verify(window).refund(admitted, Instant.now());
        // Rate first, then the local count: holding a permit while a provider budget drains would starve
        // the online chat of the few slots a background job is already using.
        verify(window, org.mockito.Mockito.times(2)).admit(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());

        capacity.release();
    }

    @Test
    void anUnconfirmedGuardRefusesInsteadOfPassingTheCallThrough() {
        var window = mock(SlidingRateWindow.class);
        when(window.admit(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new SlidingRateWindow.Outcome(SlidingRateWindow.Decision.UNAVAILABLE, 1_000,
                        "guard-unavailable", List.of()));
        var capacity = new ModelCallCapacity(1, registry, window);

        var admission = capacity.tryAcquire("agent", user, group, 10);

        // Redis being down used to admit the call silently. A limiter that is invisible is worse than a
        // visible retry, and this surface is the one that spends provider budget.
        assertThat(admission.acquired()).isFalse();
        assertThat(admission.guardUnavailable()).isTrue();
        assertThat(capacity.busy(admission).getCode()).isEqualTo("LLM_GUARD_UNAVAILABLE");
        assertThat(capacity.busy(admission).isRetryable()).isTrue();
        assertThat(registry.get("ardor.model.capacity.in.use").gauge().value()).isZero();
        verify(window, never()).refund(any(), any());
    }

    @Test
    void busyFailureIsRetryableAndUsesTheSharedBusyCode() {
        var failure = new ModelCallCapacity(1, registry).busy();

        assertThat(failure.getCode()).isEqualTo("LLM_BUSY");
        assertThat(failure.isRetryable()).isTrue();
        assertThat(failure.getRetryAfterSeconds()).isEqualTo(1);
    }
}
