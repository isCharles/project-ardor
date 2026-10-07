package com.projectardor.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class QuotaStoreTests {
    @Test
    void requestIdMarkerOutlivesTheSupportedThirtyFiveDayRetryWindow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] arguments = invocation.getArguments();
                    assertThat(Long.parseLong((String) arguments[8])).isGreaterThanOrEqualTo(35L * 86_400);
                    return 1L;
                });
        var policy = new UsagePolicy(UsageFeature.LEARNING_PLAN, 5, 500, 8, 30, Instant.now());

        assertThat(new QuotaStore(redis).consume(UUID.randomUUID(), policy, MembershipTier.MEMBER,
                UUID.randomUUID(), Instant.parse("2026-10-31T23:59:59Z")))
                .isEqualTo(QuotaStore.Decision.ALLOWED);
    }

    @Test
    void monthlyKeysUseUtcCalendarMonth() {
        UUID user = UUID.randomUUID();
        assertThat(QuotaStore.monthKey(user, UsageFeature.AGENT_CHAT,
                Instant.parse("2026-10-31T23:59:59Z"))).endsWith(":202610");
        assertThat(QuotaStore.monthKey(user, UsageFeature.AGENT_CHAT,
                Instant.parse("2026-11-01T00:00:00Z"))).endsWith(":202611");
    }

    @Test
    void mapsAtomicRedisDenialWithoutTreatingItAsSuccess() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    assertThat(script.getScriptAsString()).contains("redis.call('INCR'", "return -i",
                            "tonumber(ARGV[7])");
                    return -2L;
                });
        var store = new QuotaStore(redis);
        var policy = new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 8, 30, Instant.now());

        assertThat(store.consume(UUID.randomUUID(), policy, MembershipTier.MEMBER,
                null, Instant.parse("2026-10-03T01:00:00Z")))
                .isEqualTo(QuotaStore.Decision.USER_MINUTE_LIMIT);
    }
}
