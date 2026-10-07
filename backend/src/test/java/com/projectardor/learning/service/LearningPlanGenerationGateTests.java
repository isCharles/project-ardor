package com.projectardor.learning.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class LearningPlanGenerationGateTests {
    @Test
    void concurrentSameRequestDoesNotReachModelGeneration() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true, true, false, false);
        when(values.get(anyString())).thenReturn("same-hash", "same-hash:existing");
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LearningPlanGenerationGate gate = new LearningPlanGenerationGate(redis);

        try (var first = gate.reserve(userId, requestId, "same-hash")) {
            assertThatThrownBy(() -> gate.reserve(userId, requestId, "same-hash"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("正在生成");
        }
        verify(values, times(2)).setIfAbsent(eq("ardor:learning:create:" + userId + ":" + requestId),
                org.mockito.ArgumentMatchers.startsWith("same-hash:"), any(Duration.class));
    }

    @Test
    void reusedRequestIdCannotChangeItsPayloadAfterFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get(anyString())).thenReturn("original-hash");
        LearningPlanGenerationGate gate = new LearningPlanGenerationGate(redis);

        assertThatThrownBy(() -> gate.reserve(UUID.randomUUID(), UUID.randomUUID(), "different-hash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请求 ID");
    }
}
