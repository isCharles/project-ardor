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
                    assertThat(script.getScriptAsString()).contains("redis.call('INCR'", "return -i");
                    return -2L;
                });
        var store = new QuotaStore(redis);
        var policy = new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 8, 30, Instant.now());

        assertThat(store.consume(UUID.randomUUID(), policy, MembershipTier.MEMBER,
                null, Instant.parse("2026-10-03T01:00:00Z")))
                .isEqualTo(QuotaStore.Decision.USER_MINUTE_LIMIT);
    }
}
