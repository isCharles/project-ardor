package com.projectardor.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.projectardor.platform.usage.QuotaBurstWindow.Outcome;

/**
 * Admission order and compensation. The durable PostgreSQL counters are the last gate, so every
 * refusal before them must leave no charge behind and every refusal at them must give the Redis
 * burst units back.
 */
class QuotaServiceTests {
    private final UsagePolicyRepository policies = mock(UsagePolicyRepository.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final QuotaCounterStore counters = mock(QuotaCounterStore.class);
    private final QuotaBurstWindow burst = mock(QuotaBurstWindow.class);
    private final MembershipPlanService plans = mock(MembershipPlanService.class);
    private final UsageTokenStore tokens = mock(UsageTokenStore.class);
    private final UsagePackService packs = mock(UsagePackService.class);
    private final Instant now = Instant.parse("2026-10-31T23:59:20Z");
    private final QuotaService service = new QuotaService(policies, memberships, counters, burst, plans, tokens,
            packs, Clock.fixed(now, ZoneOffset.UTC));
    private final UsagePolicy policy = new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 500, 2000, 2, 25, 100,
            8, 120, now);

    @org.junit.jupiter.api.BeforeEach
    void stubCatalog() {
        // The plan and pack rows are seeded by migration, so a snapshot always has a current plan.
        when(plans.get(any())).thenReturn(new MembershipPlanService.Plan(MembershipTier.FREE, "免费版", "Free",
                0, "CNY", "MONTH", 50_000, false, 1, now));
        when(plans.list()).thenReturn(List.of());
        when(packs.list()).thenReturn(List.of());
    }

    @Test
    void aRequestAlreadyChargedSpendsNothingAgain() {
        UUID user = UUID.randomUUID(), requestId = UUID.randomUUID();
        when(counters.seen(user, UsageFeature.AGENT_CHAT, requestId)).thenReturn(true);

        service.consume(user, UsageFeature.AGENT_CHAT, requestId);

        verify(burst, never()).consumeUserMinute(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(counters, never()).consume(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void burstWindowsAreChargedBeforeTheDurableCounters() {
        UUID user = UUID.randomUUID();
        admit(user, MembershipTier.PLUS);
        when(counters.consume(user, UsageFeature.AGENT_CHAT, 500, 25, null, now))
                .thenReturn(QuotaDecision.ALLOWED);

        service.consume(user, UsageFeature.AGENT_CHAT, null);

        InOrder order = inOrder(burst, counters);
        order.verify(burst).consumeUserMinute(user, UsageFeature.AGENT_CHAT, 8, now);
        order.verify(burst).consumeGlobalMinute(UsageFeature.AGENT_CHAT, 120, now);
        order.verify(counters).consume(user, UsageFeature.AGENT_CHAT, 500, 25, null, now);
        verify(burst, never()).refundUserMinute(any(), any(), any());
    }

    @Test
    void aWeeklyDenialGivesBothBurstUnitsBackAndPointsAtMonday() {
        UUID user = UUID.randomUUID();
        admit(user, MembershipTier.FREE);
        when(counters.consume(user, UsageFeature.AGENT_CHAT, 5, 2, null, now))
                .thenReturn(QuotaDecision.WEEKLY_LIMIT);

        assertThatThrownBy(() -> service.consume(user, UsageFeature.AGENT_CHAT, null))
                .isInstanceOfSatisfying(QuotaExceededException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(QuotaDecision.WEEKLY_LIMIT);
                    assertThat(exception.resetAt()).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
                });

        verify(burst).refundUserMinute(user, UsageFeature.AGENT_CHAT, now);
        verify(burst).refundGlobalMinute(UsageFeature.AGENT_CHAT, now);
    }

    @Test
    void aMonthlyDenialReportsTheUtcMonthReset() {
        UUID user = UUID.randomUUID();
        admit(user, MembershipTier.FREE);
        when(counters.consume(user, UsageFeature.AGENT_CHAT, 5, 2, null, now))
                .thenReturn(QuotaDecision.MONTHLY_LIMIT);

        assertThatThrownBy(() -> service.consume(user, UsageFeature.AGENT_CHAT, null))
                .isInstanceOfSatisfying(QuotaExceededException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(QuotaDecision.MONTHLY_LIMIT);
                    assertThat(exception.resetAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
                });
        verify(burst).refundUserMinute(user, UsageFeature.AGENT_CHAT, now);
    }

    @Test
    void aUserMinuteRefusalNeverReachesTheDurableCounters() {
        UUID user = UUID.randomUUID();
        admit(user, MembershipTier.FREE);
        when(burst.consumeUserMinute(user, UsageFeature.AGENT_CHAT, 8, now)).thenReturn(Outcome.DENIED);

        assertThatDeniedWith(QuotaDecision.USER_MINUTE_LIMIT, Instant.parse("2026-11-01T00:00:00Z"),
                () -> service.consume(user, UsageFeature.AGENT_CHAT, null));
        verify(counters, never()).consume(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(burst, never()).refundUserMinute(any(), any(), any());
    }

    @Test
    void aGlobalMinuteRefusalRefundsTheUserUnit() {
        UUID user = UUID.randomUUID();
        admit(user, MembershipTier.FREE);
        when(burst.consumeGlobalMinute(UsageFeature.AGENT_CHAT, 120, now)).thenReturn(Outcome.DENIED);

        assertThatDeniedWith(QuotaDecision.GLOBAL_MINUTE_LIMIT, Instant.parse("2026-11-01T00:00:00Z"),
                () -> service.consume(user, UsageFeature.AGENT_CHAT, null));
        verify(burst).refundUserMinute(user, UsageFeature.AGENT_CHAT, now);
        verify(burst, never()).refundGlobalMinute(any(), any());
        verify(counters, never()).consume(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void anUnanswerableBurstWindowRefusesInsteadOfPassing() {
        UUID user = UUID.randomUUID();
        admit(user, MembershipTier.FREE);
        when(burst.consumeUserMinute(user, UsageFeature.AGENT_CHAT, 8, now)).thenReturn(Outcome.UNAVAILABLE);

        assertThatDeniedWith(QuotaDecision.GUARD_UNAVAILABLE, Instant.parse("2026-11-01T00:00:00Z"),
                () -> service.consume(user, UsageFeature.AGENT_CHAT, null));
        verify(counters, never()).consume(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void aLostRaceWithAnotherInstanceRefundsBurstAndAllowsTheRepeat() {
        UUID user = UUID.randomUUID(), requestId = UUID.randomUUID();
        admit(user, MembershipTier.FREE);
        when(counters.consume(user, UsageFeature.AGENT_CHAT, 5, 2, requestId, now))
                .thenReturn(QuotaDecision.DUPLICATE);

        service.consume(user, UsageFeature.AGENT_CHAT, requestId);

        verify(burst).refundUserMinute(user, UsageFeature.AGENT_CHAT, now);
        verify(burst).refundGlobalMinute(UsageFeature.AGENT_CHAT, now);
    }

    @Test
    void anUnavailableDatabaseRefusesAndGivesTheBurstUnitsBack() {
        UUID user = UUID.randomUUID();
        admit(user, MembershipTier.FREE);
        when(counters.consume(user, UsageFeature.AGENT_CHAT, 5, 2, null, now))
                .thenThrow(new IllegalStateException("database is down"));

        assertThatDeniedWith(QuotaDecision.GUARD_UNAVAILABLE, Instant.parse("2026-11-01T00:00:00Z"),
                () -> service.consume(user, UsageFeature.AGENT_CHAT, null));
        verify(burst).refundUserMinute(user, UsageFeature.AGENT_CHAT, now);
        verify(burst).refundGlobalMinute(UsageFeature.AGENT_CHAT, now);
    }

    @Test
    void tokenBudgetBlocksBeforeAnyCounterIsTouched() {
        UUID user = UUID.randomUUID();
        when(policies.get(UsageFeature.AGENT_CHAT)).thenReturn(policy);
        when(memberships.tier(user)).thenReturn(MembershipTier.FREE);
        when(plans.tokenLimit(MembershipTier.FREE)).thenReturn(100L);
        when(tokens.usedThisMonth(user, now)).thenReturn(100L);
        when(packs.balance(user)).thenReturn(0L);

        assertThatDeniedWith(QuotaDecision.TOKEN_LIMIT, Instant.parse("2026-11-01T00:00:00Z"),
                () -> service.consume(user, UsageFeature.AGENT_CHAT, UUID.randomUUID()));
        verify(burst, never()).consumeUserMinute(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(counters, never()).consume(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void anExhaustedPlanProceedsWhilePackTokensRemain() {
        UUID user = UUID.randomUUID();
        when(policies.get(UsageFeature.AGENT_CHAT)).thenReturn(policy);
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(plans.tokenLimit(MembershipTier.PLUS)).thenReturn(100L);
        when(tokens.usedThisMonth(user, now)).thenReturn(100L);
        when(packs.balance(user)).thenReturn(1_000L);
        when(burst.consumeUserMinute(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(Outcome.ALLOWED);
        when(burst.consumeGlobalMinute(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(Outcome.ALLOWED);
        when(counters.consume(user, UsageFeature.AGENT_CHAT, 500, 25, null, now))
                .thenReturn(QuotaDecision.ALLOWED);

        service.consume(user, UsageFeature.AGENT_CHAT, null);

        verify(counters).consume(user, UsageFeature.AGENT_CHAT, 500, 25, null, now);
    }

    @Test
    void snapshotReadsTheDurablePeriodRows() {
        UUID user = UUID.randomUUID();
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(policies.list()).thenReturn(List.of(
                policy,
                new UsagePolicy(UsageFeature.RESUME_ANALYSIS, 1, 40, 40, 160, 1, 10, 40, 3, 30, now)));
        Map<UsageFeature, QuotaCounterStore.PeriodUsage> usage = new EnumMap<>(UsageFeature.class);
        usage.put(UsageFeature.AGENT_CHAT, new QuotaCounterStore.PeriodUsage(12, 4));
        usage.put(UsageFeature.RESUME_ANALYSIS, new QuotaCounterStore.PeriodUsage(2, 1));
        when(counters.usage(user, now)).thenReturn(usage);

        var snapshot = service.snapshot(user);

        assertThat(snapshot.tier()).isEqualTo(MembershipTier.PLUS);
        assertThat(snapshot.features()).containsExactly(
                new QuotaService.FeatureUsage(UsageFeature.AGENT_CHAT, 12, 500, 4, 25),
                new QuotaService.FeatureUsage(UsageFeature.RESUME_ANALYSIS, 2, 40, 1, 10));
        assertThat(snapshot.resetsAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void aFreeUserSeesTheWeeklyWindowThatActuallyDeniesFirst() {
        UUID user = UUID.randomUUID();
        when(memberships.tier(user)).thenReturn(MembershipTier.FREE);
        when(policies.list()).thenReturn(List.of(policy));
        when(counters.usage(user, now)).thenReturn(Map.of(
                UsageFeature.AGENT_CHAT, new QuotaCounterStore.PeriodUsage(0, 2)));

        var feature = service.snapshot(user).features().getFirst();

        assertThat(feature.limit()).isEqualTo(5);
        assertThat(feature.weeklyLimit()).isEqualTo(2);
        assertThat(feature.weeklyUsed()).isEqualTo(2);
        assertThat(feature.weeklyLimit() - feature.weeklyUsed()).isLessThan(feature.limit() - feature.used());
    }

    @Test
    void planExpiryIsSeparateFromQuotaResetAndScopedToTheCurrentUser() {
        UUID paid = UUID.randomUUID(), free = UUID.randomUUID();
        Instant expiry = Instant.parse("2026-11-18T12:30:00Z");
        when(memberships.tier(paid)).thenReturn(MembershipTier.PLUS);
        when(memberships.tier(free)).thenReturn(MembershipTier.FREE);
        when(memberships.expiry(paid)).thenReturn(expiry);
        when(policies.list()).thenReturn(List.of());

        var snapshot = service.snapshot(paid);

        assertThat(snapshot.planExpiresAt()).isEqualTo(expiry);
        assertThat(snapshot.resetsAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(service.snapshot(free).planExpiresAt()).isNull();
        verify(memberships, never()).expiry(free);
    }

    @Test
    void snapshotReportsTokenBudgetAndPackCatalogFromDurableStores() {
        UUID user = UUID.randomUUID();
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(policies.list()).thenReturn(List.of());
        when(counters.usage(user, now)).thenReturn(Map.of());
        when(plans.get(MembershipTier.PLUS)).thenReturn(new MembershipPlanService.Plan(MembershipTier.PLUS,
                "Plus", "Plus", 3900, "CNY", "MONTHLY", 2_000_000, false, 1, now));
        when(plans.tokenLimit(MembershipTier.PLUS)).thenReturn(2_000_000L);
        when(plans.list()).thenReturn(List.of());
        when(tokens.usedThisMonth(user, now)).thenReturn(10L);
        when(packs.balance(user)).thenReturn(700L);
        when(packs.list()).thenReturn(List.of(
                new UsagePackService.Pack("PACK_1M", "轻量资源包", "Light pack", 1_000_000, 900, "CNY")));

        var snapshot = service.snapshot(user);

        assertThat(snapshot.tokens()).isEqualTo(new QuotaService.TokenUsage(10, 2_000_000, 700));
        assertThat(snapshot.packs()).containsExactly(
                new QuotaService.PackView("PACK_1M", "轻量资源包", "Light pack", 1_000_000, 900, "CNY"));
    }

    private void admit(UUID user, MembershipTier tier) {
        when(policies.get(UsageFeature.AGENT_CHAT)).thenReturn(policy);
        when(memberships.tier(user)).thenReturn(tier);
        when(burst.consumeUserMinute(user, UsageFeature.AGENT_CHAT, policy.userMinuteLimit(), now))
                .thenReturn(Outcome.ALLOWED);
        when(burst.consumeGlobalMinute(UsageFeature.AGENT_CHAT, policy.globalMinuteLimit(), now))
                .thenReturn(Outcome.ALLOWED);
    }

    private static void assertThatDeniedWith(QuotaDecision reason, Instant resetAt, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(QuotaExceededException.class, exception -> {
            assertThat(exception.reason()).isEqualTo(reason);
            assertThat(exception.resetAt()).isEqualTo(resetAt);
        });
    }
}
