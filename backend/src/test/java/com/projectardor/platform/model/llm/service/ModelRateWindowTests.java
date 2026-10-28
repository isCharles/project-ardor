package com.projectardor.platform.model.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class ModelRateWindowTests {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Instant now = Instant.parse("2026-10-28T05:06:00Z");

    @Test
    void disabledWindowAllowsWithoutTouchingRedis() {
        var window = ModelRateWindow.disabled();

        assertThat(window.enabled()).isFalse();
        assertThat(window.admit(UUID.randomUUID(), "model-a", 900, now))
                .isEqualTo(ModelRateWindow.Decision.ALLOWED);
        verifyNoInteractions(redis);
    }

    @Test
    void allowedCallChargesTheModelAndUserMinuteKeys() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            RedisScript<?> script = invocation.getArgument(0);
            assertThat(script.getScriptAsString()).contains("INCRBY", "return -3");
            var keys = invocation.<java.util.List<String>>getArgument(1);
            assertThat(keys.get(0)).contains("ardor:model:rate:model:model-a:202610280506");
            assertThat(keys.get(1)).contains("ardor:model:rate:tokens:model-a:202610280506");
            assertThat(keys.get(2)).contains("ardor:model:rate:user:");
            Object[] arguments = invocation.getArguments();
            assertThat(arguments[2]).isEqualTo("60");
            assertThat(arguments[3]).isEqualTo("100000");
            assertThat(arguments[4]).isEqualTo("20");
            assertThat(arguments[5]).isEqualTo("1234");
            assertThat(arguments[6]).isEqualTo("120");
            return 1L;
        });
        var window = new ModelRateWindow(60, 100_000, 20, redis);

        assertThat(window.admit(UUID.randomUUID(), "model-a", 1234, now))
                .isEqualTo(ModelRateWindow.Decision.ALLOWED);
    }

    @Test
    void mapsEachAtomicDenialToItsDimension() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-1L, -2L, -3L);
        var window = new ModelRateWindow(60, 100_000, 20, redis);

        assertThat(window.admit(null, "model-a", 1, now)).isEqualTo(ModelRateWindow.Decision.QPM_LIMIT);
        assertThat(window.admit(null, "model-a", 1, now)).isEqualTo(ModelRateWindow.Decision.TPM_LIMIT);
        assertThat(window.admit(null, "model-a", 1, now)).isEqualTo(ModelRateWindow.Decision.USER_QPM_LIMIT);
    }

    @Test
    void anonymousCallsDisableThePerUserDimension() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            assertThat(arguments[4]).isEqualTo("0");
            assertThat(invocation.<java.util.List<String>>getArgument(1).get(2)).contains(":anonymous:202610280506");
            return 1L;
        });
        var window = new ModelRateWindow(0, 0, 20, redis);

        assertThat(window.admit(null, "model-a", 1, now)).isEqualTo(ModelRateWindow.Decision.ALLOWED);
    }

    @Test
    void redisFailuresAndUnknownOutcomesDegradeInsteadOfBlocking() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn(null)
                .thenReturn(42L);
        var window = new ModelRateWindow(60, 0, 0, redis);

        assertThat(window.admit(UUID.randomUUID(), "model-a", 1, now))
                .isEqualTo(ModelRateWindow.Decision.DEGRADED);
        assertThat(window.admit(UUID.randomUUID(), "model-a", 1, now))
                .isEqualTo(ModelRateWindow.Decision.DEGRADED);
        assertThat(window.admit(UUID.randomUUID(), "model-a", 1, now))
                .isEqualTo(ModelRateWindow.Decision.DEGRADED);
    }
}
