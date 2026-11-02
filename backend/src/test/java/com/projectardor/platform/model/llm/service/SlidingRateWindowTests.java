package com.projectardor.platform.model.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.projectardor.platform.model.llm.domain.LlmProvider;

/**
 * The contract this service holds with {@code model-rate-admit.lua} and {@code model-rate-refund.lua}:
 * which buckets exist, what one call is charged to, and what shape the script arguments have. The
 * arithmetic inside the scripts is Redis-side, so these tests pin the keys and arguments they read.
 */
class SlidingRateWindowTests {

    private final UUID user = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");
    private final ModelRateGroup group = ModelRateGroup.of(LlmProvider.OPENAI_COMPATIBLE,
            "https://api.example.invalid/v1/", "synthetic-key", "Synthetic-Model");

    @Test
    void aWindowWithNoConfiguredBucketIsInertAndTouchesNoCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var window = new SlidingRateWindow(0, 0, 0, 0, 0, Duration.ofSeconds(1), redis);

        assertThat(window.enabled()).isFalse();
        assertThat(window.admit(user, group, 900, now).allowed()).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertThat(SlidingRateWindow.disabled().admit(user, group, 900, now).allowed()).isTrue();
    }

    @Test
    void onlyTheConfiguredDimensionsAreChargedAndEachNamesOneBucket() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L, 0L, 0L));
        var window = new SlidingRateWindow(60, 0, 0, 120_000, 0, Duration.ofSeconds(1), redis);

        var outcome = window.admit(user, group, 500, now);

        assertThat(outcome.decision()).isEqualTo(SlidingRateWindow.Decision.ALLOWED);
        assertThat(outcome.charged()).extracting(SlidingRateWindow.Bucket::dimension)
                .containsExactly("account-qpm", "model-tpm");
        assertThat(outcome.charged()).allSatisfy(bucket -> assertThat(bucket.key()).contains("{" + group.account() + "}"));
        // The token bucket is the one that pays for the estimate; the request bucket pays for one call.
        assertThat(outcome.charged()).extracting(SlidingRateWindow.Bucket::weight).containsExactly(1L, 500L);
    }

    @Test
    void oneCredentialSharesItsBucketsAcrossModelsAndNeverCarriesTheSecret() {
        var other = ModelRateGroup.of(LlmProvider.OPENAI_COMPATIBLE, "https://api.example.invalid/v1",
                "synthetic-key", "other-model");
        var otherKey = ModelRateGroup.of(LlmProvider.OPENAI_COMPATIBLE, "https://api.example.invalid/v1",
                "different-key", "Synthetic-Model");

        // Providers meter the account, not the model name: a second model on the same key must contend
        // for the same budget, and adding a model cannot bypass it.
        assertThat(group.account()).isEqualTo(other.account());
        assertThat(SlidingRateWindow.accountRequestKey(group)).isEqualTo(SlidingRateWindow.accountRequestKey(other));
        assertThat(SlidingRateWindow.modelRequestKey(group)).isNotEqualTo(SlidingRateWindow.modelRequestKey(other));
        assertThat(SlidingRateWindow.accountRequestKey(group)).isNotEqualTo(SlidingRateWindow.accountRequestKey(otherKey));
        // Keys land in Redis and in metric tags, so neither the secret nor the raw host may appear.
        assertThat(List.of(SlidingRateWindow.accountRequestKey(group), SlidingRateWindow.modelTokenKey(group),
                SlidingRateWindow.userRequestKey(group, user)))
                .allSatisfy(key -> assertThat(key).doesNotContain("synthetic-key").doesNotContain("example.invalid"));
    }

    @Test
    void everyBucketOfOneCredentialSharesOneHashTagSoTheAtomicCheckStaysInOneSlot() {
        List<String> keys = List.of(SlidingRateWindow.accountRequestKey(group), SlidingRateWindow.accountTokenKey(group),
                SlidingRateWindow.modelRequestKey(group), SlidingRateWindow.modelTokenKey(group),
                SlidingRateWindow.userRequestKey(group, user));

        assertThat(keys).allSatisfy(key -> assertThat(key.substring(key.indexOf('{') + 1, key.indexOf('}')))
                .isEqualTo(group.account()));
    }

    @Test
    void theScriptReadsThreeArgumentsPerBucketThenNowAndTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L, 0L, 0L));
        var window = new SlidingRateWindow(60, 0, 0, 0, 0, Duration.ofSeconds(1), redis);

        window.admit(user, group, 0, now);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String[]> arguments = ArgumentCaptor.forClass(String[].class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), arguments.capture());
        List<String> argv = List.of(arguments.getValue());
        assertThat(argv).hasSize(keys.getValue().size() * 3 + 2);
        // Emission for 60 requests a minute is one request every second, in microseconds; a request
        // weighs one unit; the burst is the configured second of slack.
        assertThat(argv).startsWith("1000000", "1", "1000000");
        assertThat(argv.get(argv.size() - 2)).isEqualTo(String.valueOf(now.getEpochSecond() * 1_000_000L));
        assertThat(Long.parseLong(argv.get(argv.size() - 1))).isBetween(60_000L, Duration.ofDays(1).toMillis());
    }

    @Test
    void aDenialNamesTheBucketThatForcedTheWaitAndLeavesNothingCharged() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, 2_400_000L, 2L));
        var window = new SlidingRateWindow(60, 60_000, 0, 0, 0, Duration.ofSeconds(1), redis);

        var outcome = window.admit(user, group, 400, now);

        assertThat(outcome.decision()).isEqualTo(SlidingRateWindow.Decision.LIMITED);
        assertThat(outcome.dimension()).isEqualTo("account-tpm");
        assertThat(outcome.retryAfterMs()).isEqualTo(2_400L);
        assertThat(outcome.retryAfterSeconds()).isEqualTo(3);
        assertThat(outcome.charged()).isEmpty();
    }

    @Test
    void anUnanswerableScriptRefusesTheCallInsteadOfLettingItThrough() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("connection refused"));
        var window = new SlidingRateWindow(60, 0, 0, 0, 0, Duration.ofSeconds(1), redis);

        var outcome = window.admit(user, group, 10, now);

        // The old fixed window passed traffic when Redis was unreachable. A limiter that is invisible is
        // worse than a visible retry, because the provider then prices the burst Ardor thought it capped.
        assertThat(outcome.decision()).isEqualTo(SlidingRateWindow.Decision.UNAVAILABLE);
        assertThat(outcome.dimension()).isEqualTo("guard-unavailable");
    }

    @Test
    void anUnexpectedScriptAnswerIsTreatedAsNoAnswer() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of());
        var window = new SlidingRateWindow(60, 0, 0, 0, 0, Duration.ofSeconds(1), redis);

        assertThat(window.admit(user, group, 10, now).decision())
                .isEqualTo(SlidingRateWindow.Decision.UNAVAILABLE);
    }

    @Test
    void aRefundGivesBackOneCreditPerChargedBucketAndNeverThrows() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        long nowMicros = now.getEpochSecond() * 1_000_000L;
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 0L, 0L, nowMicros + 1_000_000L, nowMicros + 400_000L));
        var window = new SlidingRateWindow(60, 60_000, 0, 0, 0, Duration.ofSeconds(1), redis);
        var admitted = window.admit(user, group, 400, now);

        window.refund(admitted, now);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String[]> arguments = ArgumentCaptor.forClass(String[].class);
        verify(redis, org.mockito.Mockito.times(2)).execute(any(RedisScript.class), keys.capture(), arguments.capture());
        List<String> refund = List.of(arguments.getAllValues().get(1));
        assertThat(refund).hasSize(admitted.charged().size() * 2 + 2);
        // One request slot (60/min = 1,000,000 microseconds), then the estimate at 60,000 tokens per
        // minute = 1,000 microseconds each, so a 400-token call owes 400,000.
        assertThat(refund).startsWith("1000000", "400000");
        // Then the horizon each charge wrote: the script answers with it, and a refund that arrives after
        // that horizon has passed is a no-op instead of a gift that erases somebody else's wait.
        assertThat(refund).containsExactly("1000000", "400000",
                String.valueOf(nowMicros + 1_000_000L), String.valueOf(nowMicros + 400_000L),
                String.valueOf(nowMicros), refund.get(refund.size() - 1));
        assertThat(Long.parseLong(refund.get(refund.size() - 1))).isBetween(60_000L, Duration.ofDays(1).toMillis());

        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("connection reset"));
        var guarded = new SlidingRateWindow(60, 0, 0, 0, 0, Duration.ofSeconds(1), broken);
        guarded.refund(guarded.admit(user, group, 10, now), now);
    }

    @Test
    void aChargeWithoutAnAnsweredHorizonIsRefundedAsTheLeastItCouldHaveOwed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // The reply an older script would give: admitted, with no per-bucket horizon to refund against.
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L, 0L, 0L));
        var window = new SlidingRateWindow(60, 0, 0, 0, 0, Duration.ofSeconds(1), redis);

        var admitted = window.admit(user, group, 0, now);

        assertThat(admitted.charged()).hasSize(1);
        assertThat(admitted.charged().getFirst().horizonMicros())
                .isEqualTo(now.getEpochSecond() * 1_000_000L + 1_000_000L);
    }

    @Test
    void burstStaysInsideOneMinuteAndNeverBelowTheCallItPrices() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L, 0L, 0L));

        for (Duration burst : List.of(Duration.ofSeconds(-5), Duration.ofSeconds(600))) {
            var window = new SlidingRateWindow(60, 0, 0, 0, 0, burst, redis);
            window.admit(user, group, 0, now);
        }

        ArgumentCaptor<String[]> arguments = ArgumentCaptor.forClass(String[].class);
        verify(redis, org.mockito.Mockito.times(2)).execute(any(RedisScript.class), anyList(), arguments.capture());
        // A negative burst would hand out free capacity and an unbounded one would outrun the limit it
        // is supposed to smooth, so the configured window ends up inside one minute — and never below
        // the cost of one call at that rate.
        assertThat(List.of(arguments.getAllValues().get(0))).containsExactly(
                "1000000", "1", "1000000", String.valueOf(now.getEpochSecond() * 1_000_000L),
                arguments.getAllValues().get(0)[4]);
        assertThat(List.of(arguments.getAllValues().get(1))).containsExactly(
                "1000000", "1", "60000000", String.valueOf(now.getEpochSecond() * 1_000_000L),
                arguments.getAllValues().get(1)[4]);
    }

    @Test
    void aSlowRateIsNeverPricedOutOfItsOwnFirstCall() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L, 0L, 0L));
        // 30 requests a minute costs two seconds of bucket time each. A one-second burst would demand
        // more than the bucket can ever hold, so every call — including the first against an empty
        // bucket — would be refused and the limiter would look like an outage.
        var window = new SlidingRateWindow(30, 0, 0, 0, 0, Duration.ofSeconds(1), redis);

        var outcome = window.admit(user, group, 0, now);

        ArgumentCaptor<String[]> arguments = ArgumentCaptor.forClass(String[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), arguments.capture());
        assertThat(List.of(arguments.getValue())).element(2).isEqualTo("2000000");
        assertThat(outcome.allowed()).isTrue();
    }

    @Test
    void anUnknownCredentialAndModelStillGetAConservativeBucket() {
        var unknown = ModelRateGroup.unattributed();

        assertThat(unknown.model()).isEqualTo("unknown");
        assertThat(SlidingRateWindow.accountRequestKey(unknown)).startsWith("ardor:model:rate:{");
    }
}
