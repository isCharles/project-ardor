package com.projectardor.platform.usage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * Short burst windows. They protect the provider and the box from a flood inside one minute; they
 * are not an entitlement, so a lost or refunded counter is only a slightly looser throttle.
 *
 * <p>Every script touches a single key, which keeps admission valid on a sharded Redis. The durable
 * period counters live in PostgreSQL ({@link QuotaCounterStore}), so a Redis flush cannot hand a
 * user a fresh monthly or weekly allowance.</p>
 */
@Repository
public class QuotaBurstWindow {
    public enum Outcome { ALLOWED, DENIED, UNAVAILABLE }

    private static final Logger log = LoggerFactory.getLogger(QuotaBurstWindow.class);
    private static final DefaultRedisScript<Long> CONSUME = script("redis/quota-burst-consume.lua");
    private static final DefaultRedisScript<Long> REFUND = script("redis/quota-burst-refund.lua");
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final int WINDOW_TTL_SECONDS = 120;

    private final StringRedisTemplate redis;

    public QuotaBurstWindow(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Outcome consumeUserMinute(UUID userId, UsageFeature feature, int limit, Instant now) {
        return consume(userKey(userId, feature, now), limit);
    }

    public Outcome consumeGlobalMinute(UsageFeature feature, int limit, Instant now) {
        return consume(globalKey(feature, now), limit);
    }

    public void refundUserMinute(UUID userId, UsageFeature feature, Instant now) {
        refund(userKey(userId, feature, now));
    }

    public void refundGlobalMinute(UsageFeature feature, Instant now) {
        refund(globalKey(feature, now));
    }

    private Outcome consume(String key, int limit) {
        try {
            Long outcome = redis.execute(CONSUME, List.of(key), String.valueOf(limit),
                    String.valueOf(WINDOW_TTL_SECONDS));
            if (outcome == null) return Outcome.UNAVAILABLE;
            return outcome.longValue() < 0 ? Outcome.DENIED : Outcome.ALLOWED;
        } catch (RuntimeException unavailable) {
            log.warn("Quota burst window unavailable; refusing admission instead of passing it: {}",
                    unavailable.getClass().getSimpleName());
            return Outcome.UNAVAILABLE;
        }
    }

    private void refund(String key) {
        try {
            redis.execute(REFUND, List.of(key));
        } catch (RuntimeException unavailable) {
            // The window lapses within two minutes, so a failed refund cannot over-grant durably.
            log.warn("Quota burst refund dropped: {}", unavailable.getClass().getSimpleName());
        }
    }

    static String userKey(UUID userId, UsageFeature feature, Instant now) {
        return "ardor:quota:burst:user:" + userId + ":" + feature + ":" + MINUTE.format(now);
    }

    static String globalKey(UsageFeature feature, Instant now) {
        return "ardor:quota:burst:global:" + feature + ":" + MINUTE.format(now);
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
