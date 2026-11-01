package com.projectardor.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.projectardor.platform.usage.QuotaBurstWindow.Outcome;

/**
 * The burst windows are the only Redis-backed layer left in quota admission, so they must stay
 * single-key (valid on a sharded Redis) and must never report success when Redis cannot answer.
 */
class QuotaBurstWindowTests {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final QuotaBurstWindow window = new QuotaBurstWindow(redis);
    private final Instant now = Instant.parse("2026-11-01T10:20:30Z");
    private final UUID user = UUID.randomUUID();

    @Test
    void aCountedMinuteAdmits() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(3L);

        assertThat(window.consumeUserMinute(user, UsageFeature.AGENT_CHAT, 8, now)).isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void everyScriptTouchesExactlyOneKeySoShardedRedisStaysValid() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        window.consumeUserMinute(user, UsageFeature.AGENT_CHAT, 8, now);
        window.consumeGlobalMinute(UsageFeature.AGENT_CHAT, 120, now);
        window.refundUserMinute(user, UsageFeature.AGENT_CHAT, now);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis, org.mockito.Mockito.times(3)).execute(any(RedisScript.class), keys.capture(),
                any(Object[].class));
        assertThat(keys.getAllValues()).allSatisfy(touched -> assertThat(touched).hasSize(1));
    }

    @Test
    void keysAreScopedToUserFeatureAndUtcMinute() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        window.consumeUserMinute(user, UsageFeature.AGENT_CHAT, 8, now);
        window.consumeGlobalMinute(UsageFeature.AGENT_CHAT, 120, now);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis, org.mockito.Mockito.times(2)).execute(any(RedisScript.class), keys.capture(),
                any(Object[].class));
        assertThat(keys.getAllValues().get(0).getFirst())
                .isEqualTo("ardor:quota:burst:user:" + user + ":AGENT_CHAT:202611011020");
        assertThat(keys.getAllValues().get(1).getFirst())
                .isEqualTo("ardor:quota:burst:global:AGENT_CHAT:202611011020");
    }

    @Test
    void aFullWindowDeniesWithoutCounting() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-1L);

        assertThat(window.consumeGlobalMinute(UsageFeature.AGENT_CHAT, 120, now)).isEqualTo(Outcome.DENIED);
    }

    @Test
    void aZeroLimitBlocksTheWindowInsteadOfDisablingIt() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    assertThat(script.getScriptAsString()).contains("if limit <= 0 then return -1 end");
                    return -1L;
                });

        assertThat(window.consumeUserMinute(user, UsageFeature.AGENT_CHAT, 0, now)).isEqualTo(Outcome.DENIED);
    }

    @Test
    void anUnansweredOrFailedScriptIsUnavailableRatherThanAdmitted() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        assertThat(window.consumeUserMinute(user, UsageFeature.AGENT_CHAT, 8, now))
                .isEqualTo(Outcome.UNAVAILABLE);

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("Redis is down"));
        assertThat(window.consumeGlobalMinute(UsageFeature.AGENT_CHAT, 120, now))
                .isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    void aRefundUsesTheUndoScriptAndNeverThrowsAtTheCaller() {
        when(redis.execute(any(RedisScript.class), anyList()))
                .thenAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    assertThat(script.getScriptAsString()).contains("redis.call('DECR'");
                    return 1L;
                });
        window.refundGlobalMinute(UsageFeature.AGENT_CHAT, now);

        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new IllegalStateException("Redis is down"));
        assertThatCode(() -> window.refundUserMinute(user, UsageFeature.AGENT_CHAT, now)).doesNotThrowAnyException();
    }
}
