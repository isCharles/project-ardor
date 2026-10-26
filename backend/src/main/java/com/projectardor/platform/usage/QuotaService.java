package com.projectardor.platform.usage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class QuotaService {
    private final UsagePolicyRepository policies;
    private final MembershipService memberships;
    private final QuotaStore store;
    private final MembershipPlanService plans;
    private final UsageTokenStore tokenStore;
    private final Clock clock;

    @Autowired
    public QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaStore store,
            MembershipPlanService plans, UsageTokenStore tokenStore) {
        this(policies, memberships, store, plans, tokenStore, Clock.systemUTC());
    }

    /** Compatibility for tests that predate plan-aware quotas. */
    QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaStore store, Clock clock) {
        this(policies, memberships, store, null, null, clock);
    }

    QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaStore store,
            MembershipPlanService plans, UsageTokenStore tokenStore, Clock clock) {
        this.policies = policies;
        this.memberships = memberships;
        this.store = store;
        this.plans = plans;
        this.tokenStore = tokenStore;
        this.clock = clock;
    }

    public void consume(UUID userId, UsageFeature feature, UUID requestId) {
        Instant now = clock.instant();
        UsagePolicy policy = policies.get(feature);
        MembershipTier tier = memberships.tier(userId);
        if (plans != null && tokenStore != null) {
            long limit = plans.tokenLimit(tier);
            long used = tokenStore.usedThisMonth(userId, now);
            if (limit > 0 && used >= limit) {
                throw new QuotaExceededException(feature, QuotaStore.Decision.TOKEN_LIMIT, nextMonth(now));
            }
        }
        QuotaStore.Decision decision = store.consume(userId, policy, tier, requestId, now);
        if (decision == QuotaStore.Decision.ALLOWED || decision == QuotaStore.Decision.DUPLICATE) return;
        Instant resetAt = decision == QuotaStore.Decision.MONTHLY_LIMIT ? nextMonth(now)
                : now.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES);
        throw new QuotaExceededException(feature, decision, resetAt);
    }

    public UsageSnapshot snapshot(UUID userId) {
        Instant now = clock.instant();
        MembershipTier tier = memberships.tier(userId);
        List<FeatureUsage> features = policies.list().stream()
                .map(policy -> new FeatureUsage(policy.feature(),
                        store.used(userId, policy.feature(), now), policy.monthlyLimit(tier)))
                .toList();
        long tokenLimit = plans == null ? 0 : plans.tokenLimit(tier);
        long tokenUsed = tokenStore == null ? 0 : tokenStore.usedThisMonth(userId, now);
        PlanView current = plans == null ? null : planView(plans.get(tier));
        List<PlanView> available = plans == null ? List.of()
                : plans.list().stream().map(QuotaService::planView).toList();
        return new UsageSnapshot(tier, current, nextMonth(now), features,
                new TokenUsage(tokenUsed, tokenLimit), available,
                tier.paid() ? memberships.expiry(userId) : null);
    }

    private static PlanView planView(MembershipPlanService.Plan plan) {
        return new PlanView(plan.code().name(), plan.nameZh(), plan.nameEn(), plan.priceCents(),
                plan.currency(), plan.billingInterval(), plan.tokenMonthlyLimit(), plan.paymentEnabled());
    }

    private Instant nextMonth(Instant now) {
        return LocalDate.of(now.atZone(ZoneOffset.UTC).getYear(),
                now.atZone(ZoneOffset.UTC).getMonthValue(), 1)
                .plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public record FeatureUsage(UsageFeature feature, int used, int limit) {}
    public record TokenUsage(long used, long limit) {}
    public record PlanView(String code, String nameZh, String nameEn, int priceCents, String currency,
            String billingInterval, long tokenLimit, boolean paymentEnabled) {}
    public record UsageSnapshot(MembershipTier tier, PlanView plan, Instant resetsAt, List<FeatureUsage> features,
            TokenUsage tokens, List<PlanView> plans, Instant planExpiresAt) {}
}
