package com.projectardor.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class QuotaStore {
    // Check all dimensions before incrementing any counter. Redis runs the script atomically.
    private static final DefaultRedisScript<Long> CONSUME = quotaScript();
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private final StringRedisTemplate redis;

    public QuotaStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static DefaultRedisScript<Long> quotaScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/quota-consume.lua"));
        script.setResultType(Long.class);
        return script;
    }

    public Decision consume(UUID userId, UsagePolicy policy, MembershipTier tier,
            UUID requestId, Instant now) {
        Instant nextMonth = LocalDate.of(now.atZone(ZoneOffset.UTC).getYear(),
                now.atZone(ZoneOffset.UTC).getMonthValue(), 1)
                .plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long monthlyTtl = Math.max(1, ChronoUnit.SECONDS.between(now, nextMonth) + 86_400);
        String monthKey = monthKey(userId, policy.feature(), now);
        String minuteSuffix = MINUTE.format(now);
        String idempotencyKey = "ardor:quota:idempotency:" + userId + ":" + policy.feature() + ":"
                + (requestId == null ? "none" : requestId);
        Long outcome = redis.execute(CONSUME,
                List.of(monthKey,
                        "ardor:quota:user-minute:" + userId + ":" + policy.feature() + ":" + minuteSuffix,
                        "ardor:quota:global-minute:" + policy.feature() + ":" + minuteSuffix,
                        idempotencyKey),
                String.valueOf(policy.monthlyLimit(tier)),
                String.valueOf(policy.userMinuteLimit()),
                String.valueOf(policy.globalMinuteLimit()),
                String.valueOf(monthlyTtl), "120", requestId == null ? "0" : "1");
        if (outcome == null) throw new IllegalStateException("额度计数器未返回结果");
        return switch (outcome.intValue()) {
            case 1 -> Decision.ALLOWED;
            case 2 -> Decision.DUPLICATE;
            case -1 -> Decision.MONTHLY_LIMIT;
            case -2 -> Decision.USER_MINUTE_LIMIT;
            case -3 -> Decision.GLOBAL_MINUTE_LIMIT;
            default -> throw new IllegalStateException("额度计数器返回了未知状态");
        };
    }

    public int used(UUID userId, UsageFeature feature, Instant now) {
        String value = redis.opsForValue().get(monthKey(userId, feature, now));
        return value == null ? 0 : Integer.parseInt(value);
    }

    static String monthKey(UUID userId, UsageFeature feature, Instant now) {
        return "ardor:quota:user-month:" + userId + ":" + feature + ":" + MONTH.format(now);
    }

    public enum Decision {
        ALLOWED, DUPLICATE, MONTHLY_LIMIT, USER_MINUTE_LIMIT, GLOBAL_MINUTE_LIMIT
    }
}
