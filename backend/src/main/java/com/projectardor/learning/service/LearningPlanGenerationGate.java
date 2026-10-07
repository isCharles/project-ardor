package com.projectardor.learning.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;

/** Prevents concurrent model calls for one user-supplied creation request. */
@Component
public class LearningPlanGenerationGate {
    private static final Logger log = LoggerFactory.getLogger(LearningPlanGenerationGate.class);
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("learning-plan-lease-").factory());

    public LearningPlanGenerationGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Reservation reserve(UUID userId, UUID requestId, String hash) {
        if (requestId == null) return () -> {};
        String key = "ardor:learning:create:" + userId + ":" + requestId;
        String bindingKey = key + ":hash";
        String bound = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(bindingKey, hash, Duration.ofDays(35)))
                ? hash : redis.opsForValue().get(bindingKey);
        if (!hash.equals(bound)) {
            throw new IllegalArgumentException("请求 ID 已用于另一份学习计划");
        }
        String token = hash + ":" + UUID.randomUUID();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, LEASE))) {
            String active = redis.opsForValue().get(key);
            if (active != null && !active.startsWith(hash + ":")) {
                throw new IllegalArgumentException("请求 ID 已用于另一份学习计划");
            }
            throw new IllegalStateException("这份学习计划正在生成，请稍后重试");
        }
        ScheduledFuture<?> renewal = renewals.scheduleAtFixedRate(() -> {
            try {
                redis.execute(RENEW, List.of(key), token, String.valueOf(LEASE.toMillis()));
            } catch (RuntimeException exception) {
                log.warn("Could not renew learning-plan generation lease", exception);
            }
        }, 1, 1, TimeUnit.MINUTES);
        return () -> {
            renewal.cancel(false);
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (RuntimeException exception) {
                // A successful database commit must not be reported as failure because Redis is down.
                log.warn("Could not release learning-plan generation lease", exception);
            }
        };
    }

    @PreDestroy
    void shutdown() { renewals.shutdownNow(); }

    public interface Reservation extends AutoCloseable {
        @Override void close();
    }
}
