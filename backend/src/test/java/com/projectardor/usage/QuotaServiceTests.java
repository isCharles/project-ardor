package com.projectardor.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
        UsagePolicy policy = new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 8, 120, now);
        when(policies.get(UsageFeature.AGENT_CHAT)).thenReturn(policy);
        when(memberships.tier(user)).thenReturn(MembershipTier.MEMBER);
        when(store.consume(user, policy, MembershipTier.MEMBER, requestId, now))
                .thenReturn(QuotaStore.Decision.DUPLICATE);

        service.consume(user, UsageFeature.AGENT_CHAT, requestId);

        verify(store).consume(user, policy, MembershipTier.MEMBER, requestId, now);
        assertThat(policy.monthlyLimit(MembershipTier.MEMBER)).isEqualTo(500);
    }

    @Test
    void monthlyExhaustionReturnsNextUtcMonth() {
        UUID user = UUID.randomUUID();
        UsagePolicy policy = new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 8, 120, now);
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
        when(memberships.tier(user)).thenReturn(MembershipTier.MEMBER);
        when(policies.list()).thenReturn(List.of(
                new UsagePolicy(UsageFeature.AGENT_CHAT, 5, 500, 8, 120, now),
                new UsagePolicy(UsageFeature.RESUME_ANALYSIS, 1, 40, 3, 30, now)));
        when(store.used(user, UsageFeature.AGENT_CHAT, now)).thenReturn(12);
        when(store.used(user, UsageFeature.RESUME_ANALYSIS, now)).thenReturn(2);

        var snapshot = service.snapshot(user);

        assertThat(snapshot.tier()).isEqualTo(MembershipTier.MEMBER);
        assertThat(snapshot.features()).containsExactly(
                new QuotaService.FeatureUsage(UsageFeature.AGENT_CHAT, 12, 500),
                new QuotaService.FeatureUsage(UsageFeature.RESUME_ANALYSIS, 2, 40));
    }
}
