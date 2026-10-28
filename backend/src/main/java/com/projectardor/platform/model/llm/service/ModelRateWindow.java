package com.projectardor.platform.model.llm.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Approximate cross-instance QPM/TPM admission for model calls.
 *
 * <p>Counters live in fixed UTC-minute Redis keys, so every instance sharing the cache sees the same
 * budget. TPM charges the pre-send token estimate (input plus reserved output on the agent path),
 * not the provider's reported usage. Zero disables a dimension; when Redis is unavailable the call
 * is admitted and reported as degraded instead of failing the turn.</p>
 */
@Component
public class ModelRateWindow {
    private static final DefaultRedisScript<Long> CONSUME = consumeScript();
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final int WINDOW_TTL_SECONDS = 120;

    private final StringRedisTemplate redis;
    private final int qpm;
    private final int tpm;
    private final int userQpm;

    @Autowired
    public ModelRateWindow(@Value("${app.model.qpm:0}") int qpm,
            @Value("${app.model.tpm:0}") int tpm,
            @Value("${app.model.user-qpm:0}") int userQpm,
            StringRedisTemplate redis) {
        this.qpm = Math.max(0, qpm);
        this.tpm = Math.max(0, tpm);
        this.userQpm = Math.max(0, userQpm);
        this.redis = redis;
    }

    /** Used by non-Spring constructors in tests and compatibility paths. */
    public static ModelRateWindow disabled() {
        return new ModelRateWindow(0, 0, 0, null);
    }

    public boolean enabled() {
        return redis != null && (qpm > 0 || tpm > 0 || userQpm > 0);
    }

    public Decision admit(UUID userId, String modelKey, long estimatedTokens, Instant now) {
        if (!enabled()) return Decision.ALLOWED;
        String minute = MINUTE.format(now);
        String model = modelKey == null || modelKey.isBlank() ? "unknown" : modelKey;
        int userLimit = userId == null ? 0 : userQpm;
        try {
            Long outcome = redis.execute(CONSUME,
                    List.of("ardor:model:rate:model:" + model + ":" + minute,
                            "ardor:model:rate:tokens:" + model + ":" + minute,
                            "ardor:model:rate:user:" + (userId == null ? "anonymous" : userId) + ":" + minute),
                    String.valueOf(qpm), String.valueOf(tpm), String.valueOf(userLimit),
                    String.valueOf(Math.max(0, estimatedTokens)), String.valueOf(WINDOW_TTL_SECONDS));
            if (outcome == null) return Decision.DEGRADED;
            return switch (outcome.intValue()) {
                case 1 -> Decision.ALLOWED;
                case -1 -> Decision.QPM_LIMIT;
                case -2 -> Decision.TPM_LIMIT;
                case -3 -> Decision.USER_QPM_LIMIT;
                default -> Decision.DEGRADED;
            };
        } catch (RuntimeException unavailable) {
            return Decision.DEGRADED;
        }
    }

    private static DefaultRedisScript<Long> consumeScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/model-rate-consume.lua"));
        script.setResultType(Long.class);
        return script;
    }

    public enum Decision {
        ALLOWED, QPM_LIMIT, TPM_LIMIT, USER_QPM_LIMIT, DEGRADED
    }
}
