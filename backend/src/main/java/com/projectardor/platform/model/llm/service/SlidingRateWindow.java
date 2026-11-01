package com.projectardor.platform.model.llm.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Cross-instance admission against the rate a model provider actually publishes.
 *
 * <p>Each bucket is a generic GCRA token bucket, so capacity replenishes continuously instead of
 * resetting on a calendar edge: a caller cannot get twice the configured rate by straddling a minute,
 * which is what the previous fixed-window counter allowed. Providers document account or organisation
 * level limits, so {@link ModelRateGroup} keys the request and token buckets on the credential and
 * every model behind it competes for the same budget.</p>
 *
 * <p>Admission is one script over all buckets, and every key carries the account's hash tag, so a
 * denial charges nothing and the script stays valid on a sharded Redis. {@code burst} is a floor, not a
 * ceiling: each bucket tolerates at least the cost of the call it is judging, so a slow rate can still
 * make progress. When Redis cannot answer, the window reports {@link Decision#UNAVAILABLE} and the
 * caller refuses the call rather than passing it through: a rate limiter that is invisible is worse
 * than a visible retry.</p>
 */
@Component
public class SlidingRateWindow {
    public enum Decision { ALLOWED, LIMITED, UNAVAILABLE }

    /**
     * One bucket inside a single credential group.
     *
     * @param horizonMicros when the debt this call wrote clears; only a charge whose horizon is still in
     *                      the future can be given back, so a charge that has already drained cannot
     *                      shorten the wait a later call paid for
     */
    public record Bucket(String key, String dimension, long emissionMicros, long weight, long horizonMicros) {
        Bucket(String key, String dimension, long emissionMicros, long weight) {
            this(key, dimension, emissionMicros, weight, 0);
        }

        long creditMicros() {
            return weight * emissionMicros;
        }

        Bucket chargedUntil(long horizon) {
            return new Bucket(key, dimension, emissionMicros, weight, horizon);
        }
    }

    /**
     * @param retryAfterMs how long this bucket needs to drain before the call could pass
     * @param charged      the buckets this outcome paid for; pass back to {@link #refund} if the call
     *                     is then refused by a later layer
     */
    public record Outcome(Decision decision, long retryAfterMs, String dimension, List<Bucket> charged) {
        static final Outcome ALLOWED = new Outcome(Decision.ALLOWED, 0, null, List.of());

        public boolean allowed() {
            return decision == Decision.ALLOWED;
        }

        public int retryAfterSeconds() {
            return (int) Math.max(1, Math.ceil(retryAfterMs / 1000.0));
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SlidingRateWindow.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ADMIT = admitScript();
    private static final DefaultRedisScript<Long> REFUND = refundScript();
    private static final long MICROSECONDS_PER_MINUTE = 60L * 1_000_000L;
    private static final long MAX_TTL_MILLIS = Duration.ofDays(1).toMillis();

    private final StringRedisTemplate redis;
    private final long accountRequests;
    private final long accountTokens;
    private final long modelRequests;
    private final long modelTokens;
    private final long userRequests;
    private final long burstMicros;

    public SlidingRateWindow(@Value("${app.model.rate.account-qpm:0}") long accountRequests,
            @Value("${app.model.rate.account-tpm:0}") long accountTokens,
            @Value("${app.model.rate.model-qpm:0}") long modelRequests,
            @Value("${app.model.rate.model-tpm:0}") long modelTokens,
            @Value("${app.model.rate.user-qpm:0}") long userRequests,
            @Value("${app.model.rate.burst:1s}") Duration burst,
            StringRedisTemplate redis) {
        this.accountRequests = Math.max(0, accountRequests);
        this.accountTokens = Math.max(0, accountTokens);
        this.modelRequests = Math.max(0, modelRequests);
        this.modelTokens = Math.max(0, modelTokens);
        this.userRequests = Math.max(0, userRequests);
        this.burstMicros = burst == null || burst.isNegative() ? 0 : Math.min(burst.toNanos() / 1_000,
                MICROSECONDS_PER_MINUTE);
        this.redis = redis;
    }

    /** Test and non-Spring constructor: every bucket disabled, so the window is inert. */
    public static SlidingRateWindow disabled() {
        return new SlidingRateWindow(0, 0, 0, 0, 0, Duration.ZERO, null);
    }

    public boolean enabled() {
        return redis != null && (accountRequests > 0 || accountTokens > 0 || modelRequests > 0
                || modelTokens > 0 || userRequests > 0);
    }

    /**
     * Charges one request and {@code tokens} worth of capacity across every enabled bucket.
     *
     * @param tokens what this call may cost upstream; the caller decides whether reserved output is
     *               included, because providers differ on that (Anthropic excludes {@code max_tokens}
     *               from its output budget, Moonshot reserves the sum of prompt and completion up
     *               front), so this figure is an upper-bound estimate rather than a billed amount
     */
    public Outcome admit(UUID userId, ModelRateGroup group, long tokens, Instant now) {
        if (!enabled()) return Outcome.ALLOWED;
        List<Bucket> buckets = buckets(userId, group == null ? ModelRateGroup.unattributed() : group, tokens);
        if (buckets.isEmpty()) return Outcome.ALLOWED;
        long nowMicros = micros(now);
        List<String> arguments = new ArrayList<>(buckets.size() * 3 + 2);
        for (Bucket bucket : buckets) {
            arguments.add(String.valueOf(bucket.emissionMicros()));
            arguments.add(String.valueOf(bucket.weight()));
            arguments.add(String.valueOf(tolerance(bucket)));
        }
        arguments.add(String.valueOf(nowMicros));
        arguments.add(String.valueOf(ttlMillis(buckets)));
        try {
            List<?> result = redis.execute(ADMIT, buckets.stream().map(Bucket::key).toList(),
                    arguments.toArray(String[]::new));
            if (result == null || result.size() < 3) return unavailable(buckets);
            if (asLong(result.get(0)) == 1) {
                return new Outcome(Decision.ALLOWED, 0, null, charged(buckets, result, nowMicros));
            }
            long wait = asLong(result.get(1));
            int index = (int) asLong(result.get(2));
            String dimension = index >= 1 && index <= buckets.size() ? buckets.get(index - 1).dimension() : "rate";
            return new Outcome(Decision.LIMITED, Math.max(1, wait / 1000), dimension, List.of());
        } catch (RuntimeException failure) {
            return unavailable(buckets);
        }
    }

    /**
     * Gives back what an admitted call paid for, because a later layer refused it. A lost refund is
     * bounded by one request or token cost and drains on its own, so it is logged, not retried.
     */
    public void refund(Outcome admitted, Instant now) {
        if (!enabled() || admitted == null || admitted.charged().isEmpty()) return;
        long nowMicros = micros(now);
        List<String> keys = new ArrayList<>(admitted.charged().size());
        List<String> arguments = new ArrayList<>(admitted.charged().size() * 2 + 2);
        for (Bucket bucket : admitted.charged()) {
            keys.add(bucket.key());
            arguments.add(String.valueOf(bucket.creditMicros()));
        }
        for (Bucket bucket : admitted.charged()) {
            arguments.add(String.valueOf(bucket.horizonMicros()));
        }
        arguments.add(String.valueOf(nowMicros));
        arguments.add(String.valueOf(ttlMillis(admitted.charged())));
        try {
            redis.execute(REFUND, keys, arguments.toArray(String[]::new));
        } catch (RuntimeException failure) {
            log.warn("Model rate refund failed; the bucket drains on its own: {}",
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Pair each bucket this call paid into with the horizon its write left behind, in the order the
     * script answered. A reply that stops short of the horizons falls back to the least this call could
     * have owed, so an early refund still gives the capacity back and a late one gives nothing back.
     */
    private List<Bucket> charged(List<Bucket> buckets, List<?> result, long nowMicros) {
        List<Bucket> charged = new ArrayList<>(buckets.size());
        for (int index = 0; index < buckets.size(); index++) {
            Bucket bucket = buckets.get(index);
            long horizon = index + 3 < result.size() ? asLong(result.get(index + 3))
                    : nowMicros + bucket.creditMicros();
            charged.add(bucket.chargedUntil(horizon));
        }
        return charged;
    }

    private List<Bucket> buckets(UUID userId, ModelRateGroup group, long tokens) {
        long weight = Math.max(0, tokens);
        List<Bucket> buckets = new ArrayList<>(5);
        add(buckets, accountRequestKey(group), "account-qpm", accountRequests, 1);
        add(buckets, accountTokenKey(group), "account-tpm", accountTokens, weight);
        add(buckets, modelRequestKey(group), "model-qpm", modelRequests, 1);
        add(buckets, modelTokenKey(group), "model-tpm", modelTokens, weight);
        if (userId != null) add(buckets, userRequestKey(group, userId), "user-qpm", userRequests, 1);
        return buckets;
    }

    private void add(List<Bucket> buckets, String key, String dimension, long perMinute, long weight) {
        long emission = emission(perMinute);
        if (emission <= 0) return;
        buckets.add(new Bucket(key, dimension, emission, weight));
    }

    /**
     * The tolerance a bucket really grants: the configured burst, but never less than the cost of the
     * call being judged. Without the floor a rate slower than one call per burst window (30 requests a
     * minute costs 2,000,000 microseconds each against a 1s burst) could never admit anything, not even
     * the first call against an empty bucket.
     */
    private long tolerance(Bucket bucket) {
        return Math.max(burstMicros, bucket.creditMicros());
    }

    private Outcome unavailable(List<Bucket> buckets) {
        String dimensions = buckets.stream().map(Bucket::dimension).reduce((left, right) -> left + "," + right)
                .orElse("rate");
        log.warn("Model rate window unavailable; refusing the call instead of passing it: buckets={}", dimensions);
        return new Outcome(Decision.UNAVAILABLE, 1_000, "guard-unavailable", List.of());
    }

    /** Microseconds each unit costs; zero disables a bucket. */
    private static long emission(long perMinute) {
        return perMinute <= 0 ? 0 : Math.max(1, MICROSECONDS_PER_MINUTE / perMinute);
    }

    /** Keep a bucket alive for as long as its deepest possible debt, plus a minute of slack. */
    private long ttlMillis(List<Bucket> buckets) {
        long horizon = 0;
        for (Bucket bucket : buckets) {
            horizon = Math.max(horizon, bucket.creditMicros() + tolerance(bucket));
        }
        return Math.min(MAX_TTL_MILLIS, 60_000 + horizon / 1000);
    }

    // Every key for one credential carries the same hash tag, so the atomic check stays in one slot.
    static String accountRequestKey(ModelRateGroup group) {
        return slot(group, "requests");
    }

    static String accountTokenKey(ModelRateGroup group) {
        return slot(group, "tokens");
    }

    static String modelRequestKey(ModelRateGroup group) {
        return slot(group, "model:" + group.model() + ":requests");
    }

    static String modelTokenKey(ModelRateGroup group) {
        return slot(group, "model:" + group.model() + ":tokens");
    }

    static String userRequestKey(ModelRateGroup group, UUID userId) {
        return slot(group, "user:" + userId + ":requests");
    }

    private static String slot(ModelRateGroup group, String suffix) {
        return "ardor:model:rate:{" + group.account() + "}:" + suffix;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static long micros(Instant now) {
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> admitScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/model-rate-admit.lua"));
        script.setResultType(List.class);
        return script;
    }

    private static DefaultRedisScript<Long> refundScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/model-rate-refund.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
