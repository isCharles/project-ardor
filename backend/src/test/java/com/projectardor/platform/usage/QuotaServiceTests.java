package com.projectardor.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class QuotaServiceTests {
    private final UsagePolicyRepository policies = mock(UsagePolicyRepository.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final QuotaStore store = mock(QuotaStore.class);
    private final Instant now = Instant.parse("2026-10-31T23:59:20Z");
    private final QuotaService service = new QuotaService(policies, memberships, store,
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void giftedMemberUsesMemberLimitAndAllowsIdempotentRetry() {
        UUID user = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UsagePolicy policy = new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 500, 2000, 2, 25, 100, 8, 120, now);
        when(policies.get(UsageFeature.AGENT_CHAT)).thenReturn(policy);
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(store.consume(user, policy, MembershipTier.PLUS, requestId, now))
                .thenReturn(QuotaStore.Decision.DUPLICATE);

        service.consume(user, UsageFeature.AGENT_CHAT, requestId);

        verify(store).consume(user, policy, MembershipTier.PLUS, requestId, now);
        assertThat(policy.monthlyLimit(MembershipTier.PLUS)).isEqualTo(500);
    }

    @Test
    void monthlyExhaustionReturnsNextUtcMonth() {
        UUID user = UUID.randomUUID();
        UsagePolicy policy = new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 500, 2000, 2, 25, 100, 8, 120, now);
        when(policies.get(UsageFeature.AGENT_CHAT)).thenReturn(policy);
        when(memberships.tier(user)).thenReturn(MembershipTier.FREE);
        when(store.consume(user, policy, MembershipTier.FREE, null, now))
                .thenReturn(QuotaStore.Decision.MONTHLY_LIMIT);

        assertThatThrownBy(() -> service.consume(user, UsageFeature.AGENT_CHAT, null))
                .isInstanceOfSatisfying(QuotaExceededException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(QuotaStore.Decision.MONTHLY_LIMIT);
                    assertThat(exception.resetAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
                });
    }

    @Test
    void snapshotSeparatesLimitsByFeature() {
        UUID user = UUID.randomUUID();
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(policies.list()).thenReturn(List.of(
                new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 500, 2000, 2, 25, 100, 8, 120, now),
                new UsagePolicy(UsageFeature.RESUME_ANALYSIS, 1, 40, 40, 160, 1, 10, 40, 3, 30, now)));
        when(store.used(user, UsageFeature.AGENT_CHAT, now)).thenReturn(12);
        when(store.used(user, UsageFeature.RESUME_ANALYSIS, now)).thenReturn(2);
        when(store.usedThisWeek(user, UsageFeature.AGENT_CHAT, now)).thenReturn(4);
        when(store.usedThisWeek(user, UsageFeature.RESUME_ANALYSIS, now)).thenReturn(1);

        var snapshot = service.snapshot(user);

        assertThat(snapshot.tier()).isEqualTo(MembershipTier.PLUS);
        assertThat(snapshot.features()).containsExactly(
                new QuotaService.FeatureUsage(UsageFeature.AGENT_CHAT, 12, 500, 4, 25),
                new QuotaService.FeatureUsage(UsageFeature.RESUME_ANALYSIS, 2, 40, 1, 10));
    }

    @Test
    void snapshotReportsTheWeeklyBudgetThatActuallyDeniesAFreeUser() {
        UUID user = UUID.randomUUID();
        when(memberships.tier(user)).thenReturn(MembershipTier.FREE);
        when(policies.list()).thenReturn(List.of(
                new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 500, 2000, 2, 25, 100, 8, 120, now)));
        when(store.used(user, UsageFeature.AGENT_CHAT, now)).thenReturn(0);
        when(store.usedThisWeek(user, UsageFeature.AGENT_CHAT, now)).thenReturn(2);

        var feature = service.snapshot(user).features().getFirst();

        assertThat(feature.limit()).isEqualTo(5);
        assertThat(feature.weeklyLimit()).isEqualTo(2);
        assertThat(feature.weeklyUsed()).isEqualTo(2);
        // The panel must be able to tell that this user is spent even though the month shows 0 / 5.
        assertThat(feature.weeklyLimit() - feature.weeklyUsed())
                .isLessThan(feature.limit() - feature.used());
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
    void tokenBudgetBlocksBeforeTheModuleCounter() {
        var plans = mock(MembershipPlanService.class);
        var tokens = mock(UsageTokenStore.class);
        var budgetService = new QuotaService(policies, memberships, store, plans, tokens,
                Clock.fixed(now, ZoneOffset.UTC));
        UUID user = UUID.randomUUID();
        UsagePolicy policy = new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 500, 2000, 2, 25, 100, 8, 120, now);
        when(policies.get(UsageFeature.AGENT_CHAT)).thenReturn(policy);
        when(memberships.tier(user)).thenReturn(MembershipTier.FREE);
        when(plans.tokenLimit(MembershipTier.FREE)).thenReturn(100L);
        when(tokens.usedThisMonth(user, now)).thenReturn(100L);

        assertThatThrownBy(() -> budgetService.consume(user, UsageFeature.AGENT_CHAT, UUID.randomUUID()))
                .isInstanceOfSatisfying(QuotaExceededException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(QuotaStore.Decision.TOKEN_LIMIT);
                    assertThat(exception.resetAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
                });
        verify(store, never()).consume(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anExhaustedPlanIsAllowedWhilePackTokensRemain() {
        var plans = mock(MembershipPlanService.class);
        var tokens = mock(UsageTokenStore.class);
        var packs = mock(UsagePackService.class);
        var budgetService = new QuotaService(policies, memberships, store, plans, tokens, packs,
                Clock.fixed(now, ZoneOffset.UTC));
        UUID user = UUID.randomUUID();
        UsagePolicy policy = new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 500, 2000, 2, 25, 100, 8, 120, now);
        when(policies.get(UsageFeature.AGENT_CHAT)).thenReturn(policy);
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(plans.tokenLimit(MembershipTier.PLUS)).thenReturn(100L);
        when(tokens.usedThisMonth(user, now)).thenReturn(100L);
        when(packs.balance(user)).thenReturn(1_000L);
        when(store.consume(user, policy, MembershipTier.PLUS, null, now)).thenReturn(QuotaStore.Decision.ALLOWED);

        budgetService.consume(user, UsageFeature.AGENT_CHAT, null);

        verify(store).consume(user, policy, MembershipTier.PLUS, null, now);
    }

    @Test
    void anExhaustedPlanWithoutPackTokensStillBlocks() {
        var plans = mock(MembershipPlanService.class);
        var tokens = mock(UsageTokenStore.class);
        var packs = mock(UsagePackService.class);
        var budgetService = new QuotaService(policies, memberships, store, plans, tokens, packs,
                Clock.fixed(now, ZoneOffset.UTC));
        UUID user = UUID.randomUUID();
        UsagePolicy policy = new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 500, 2000, 2, 25, 100, 8, 120, now);
        when(policies.get(UsageFeature.AGENT_CHAT)).thenReturn(policy);
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(plans.tokenLimit(MembershipTier.PLUS)).thenReturn(100L);
        when(tokens.usedThisMonth(user, now)).thenReturn(100L);
        when(packs.balance(user)).thenReturn(0L);

        assertThatThrownBy(() -> budgetService.consume(user, UsageFeature.AGENT_CHAT, null))
                .isInstanceOfSatisfying(QuotaExceededException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(QuotaStore.Decision.TOKEN_LIMIT));
        verify(store, never()).consume(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void snapshotReportsPackBalanceAndCatalog() {
        var plans = mock(MembershipPlanService.class);
        var tokens = mock(UsageTokenStore.class);
        var packs = mock(UsagePackService.class);
        var budgetService = new QuotaService(policies, memberships, store, plans, tokens, packs,
                Clock.fixed(now, ZoneOffset.UTC));
        UUID user = UUID.randomUUID();
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(policies.list()).thenReturn(List.of());
        when(plans.get(MembershipTier.PLUS)).thenReturn(new MembershipPlanService.Plan(MembershipTier.PLUS,
                "Plus", "Plus", 3900, "CNY", "MONTHLY", 2_000_000, false, 1, now));
        when(plans.tokenLimit(MembershipTier.PLUS)).thenReturn(2_000_000L);
        when(plans.list()).thenReturn(List.of());
        when(tokens.usedThisMonth(user, now)).thenReturn(10L);
        when(packs.balance(user)).thenReturn(700L);
        when(packs.list()).thenReturn(List.of(
                new UsagePackService.Pack("PACK_1M", "轻量资源包", "Light pack", 1_000_000, 900, "CNY")));

        var snapshot = budgetService.snapshot(user);

        assertThat(snapshot.tokens()).isEqualTo(new QuotaService.TokenUsage(10, 2_000_000, 700));
        assertThat(snapshot.packs()).containsExactly(
                new QuotaService.PackView("PACK_1M", "轻量资源包", "Light pack", 1_000_000, 900, "CNY"));
    }

}
