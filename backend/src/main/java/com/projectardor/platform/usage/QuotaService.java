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
    private final UsagePackService packs;
    private final Clock clock;

    @Autowired
    public QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaStore store,
            MembershipPlanService plans, UsageTokenStore tokenStore, UsagePackService packs) {
        this(policies, memberships, store, plans, tokenStore, packs, Clock.systemUTC());
    }

    /** Compatibility for tests that predate plan-aware quotas. */
    QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaStore store, Clock clock) {
        this(policies, memberships, store, null, null, null, clock);
    }

    QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaStore store,
            MembershipPlanService plans, UsageTokenStore tokenStore, Clock clock) {
        this(policies, memberships, store, plans, tokenStore, null, clock);
    }

    QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaStore store,
            MembershipPlanService plans, UsageTokenStore tokenStore, UsagePackService packs, Clock clock) {
        this.policies = policies;
        this.memberships = memberships;
        this.store = store;
        this.plans = plans;
        this.tokenStore = tokenStore;
        this.packs = packs;
        this.clock = clock;
    }

    public void consume(UUID userId, UsageFeature feature, UUID requestId) {
        Instant now = clock.instant();
        UsagePolicy policy = policies.get(feature);
        MembershipTier tier = memberships.tier(userId);
        if (plans != null && tokenStore != null) {
            long limit = plans.tokenLimit(tier);
            long used = tokenStore.usedThisMonth(userId, now);
            if (limit > 0 && used >= limit && (packs == null || packs.balance(userId) <= 0)) {
                throw new QuotaExceededException(feature, QuotaStore.Decision.TOKEN_LIMIT, nextMonth(now));
            }
        }
        QuotaStore.Decision decision = store.consume(userId, policy, tier, requestId, now);
        if (decision == QuotaStore.Decision.ALLOWED || decision == QuotaStore.Decision.DUPLICATE) return;
        Instant resetAt = switch (decision) {
            case MONTHLY_LIMIT -> nextMonth(now);
            case WEEKLY_LIMIT -> nextWeek(now);
            default -> now.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES);
        };
        throw new QuotaExceededException(feature, decision, resetAt);
    }

    public UsageSnapshot snapshot(UUID userId) {
        Instant now = clock.instant();
        MembershipTier tier = memberships.tier(userId);
        List<FeatureUsage> features = policies.list().stream()
                .map(policy -> new FeatureUsage(policy.feature(),
                        store.used(userId, policy.feature(), now), policy.monthlyLimit(tier),
                        store.usedThisWeek(userId, policy.feature(), now), policy.weeklyLimit(tier)))
                .toList();
        long tokenLimit = plans == null ? 0 : plans.tokenLimit(tier);
        long tokenUsed = tokenStore == null ? 0 : tokenStore.usedThisMonth(userId, now);
        long packBalance = packs == null ? 0 : packs.balance(userId);
        List<PackView> availablePacks = packs == null ? List.of()
                : packs.list().stream().map(QuotaService::packView).toList();
        PlanView current = plans == null ? null : planView(plans.get(tier));
        List<PlanView> available = plans == null ? List.of()
                : plans.list().stream().map(QuotaService::planView).toList();
        return new UsageSnapshot(tier, current, nextMonth(now), features,
                new TokenUsage(tokenUsed, tokenLimit, packBalance), available, availablePacks,
                tier.paid() ? memberships.expiry(userId) : null);
    }

    private static PlanView planView(MembershipPlanService.Plan plan) {
        return new PlanView(plan.code().name(), plan.nameZh(), plan.nameEn(), plan.priceCents(),
                plan.currency(), plan.billingInterval(), plan.tokenMonthlyLimit(), plan.paymentEnabled());
    }

    private static PackView packView(UsagePackService.Pack pack) {
        return new PackView(pack.code(), pack.nameZh(), pack.nameEn(), pack.tokenAmount(), pack.priceCents(),
                pack.currency());
    }

    private Instant nextMonth(Instant now) {
        return LocalDate.of(now.atZone(ZoneOffset.UTC).getYear(),
                now.atZone(ZoneOffset.UTC).getMonthValue(), 1)
                .plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant nextWeek(Instant now) {
        var date = now.atZone(ZoneOffset.UTC).toLocalDate();
        var nextMonday = date.plusDays((8 - date.getDayOfWeek().getValue()) % 7);
        if (nextMonday.equals(date)) nextMonday = date.plusWeeks(1);
        return nextMonday.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public record FeatureUsage(UsageFeature feature, int used, int limit, int weeklyUsed, int weeklyLimit) {}
    public record TokenUsage(long used, long limit, long packBalance) {}
    public record PlanView(String code, String nameZh, String nameEn, int priceCents, String currency,
            String billingInterval, long tokenLimit, boolean paymentEnabled) {}
    public record PackView(String code, String nameZh, String nameEn, long tokenAmount, int priceCents,
            String currency) {}
    public record UsageSnapshot(MembershipTier tier, PlanView plan, Instant resetsAt, List<FeatureUsage> features,
            TokenUsage tokens, List<PlanView> plans, List<PackView> packs, Instant planExpiresAt) {}
}
