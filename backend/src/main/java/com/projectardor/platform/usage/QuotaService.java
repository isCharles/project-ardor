package com.projectardor.platform.usage;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.projectardor.platform.usage.QuotaBurstWindow.Outcome;

/**
 * Admission for one business operation.
 *
 * <p>Layers, in the order they are charged, cheapest and most self-healing first:</p>
 * <ol>
 *   <li>the monthly token budget, read from the durable token ledger;</li>
 *   <li>the Redis burst windows, which are throttles and are refunded if a later layer refuses;</li>
 *   <li>the durable monthly and weekly feature counters in PostgreSQL, which are the last gate and
 *       decide every period dimension in one transaction.</li>
 * </ol>
 *
 * <p>The durable layer is deliberately last: nothing it writes can be undone without a crash window,
 * while an over-counted burst unit lapses within two minutes. When either guard cannot answer, the
 * request is refused with {@link QuotaDecision#GUARD_UNAVAILABLE} rather than passed through.</p>
 */
@Service
public class QuotaService {
    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final UsagePolicyRepository policies;
    private final MembershipService memberships;
    private final QuotaCounterStore counters;
    private final QuotaBurstWindow burst;
    private final MembershipPlanService plans;
    private final UsageTokenStore tokenStore;
    private final UsagePackService packs;
    private final Clock clock;

    @Autowired
    public QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaCounterStore counters,
            QuotaBurstWindow burst, MembershipPlanService plans, UsageTokenStore tokenStore, UsagePackService packs) {
        this(policies, memberships, counters, burst, plans, tokenStore, packs, Clock.systemUTC());
    }

    public QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaCounterStore counters,
            QuotaBurstWindow burst, MembershipPlanService plans, UsageTokenStore tokenStore, UsagePackService packs,
            Clock clock) {
        this.policies = policies;
        this.memberships = memberships;
        this.counters = counters;
        this.burst = burst;
        this.plans = plans;
        this.tokenStore = tokenStore;
        this.packs = packs;
        this.clock = clock;
    }

    public void consume(UUID userId, UsageFeature feature, UUID requestId) {
        Instant now = clock.instant();
        UsagePolicy policy = policies.get(feature);
        MembershipTier tier = memberships.tier(userId);
        guardTokenBudget(userId, feature, tier, now);
        // A repeat of an already charged request must not even touch the burst windows.
        if (counters.seen(userId, feature, requestId)) return;
        chargeBurstWindows(userId, feature, policy, now);
        QuotaDecision decision;
        try {
            decision = counters.consume(userId, feature, policy.monthlyLimit(tier), policy.weeklyLimit(tier),
                    requestId, now);
        } catch (RuntimeException unavailable) {
            refundBurstWindows(userId, feature, now);
            log.warn("Durable quota counters unavailable; refusing admission: {}",
                    unavailable.getClass().getSimpleName());
            throw guardUnavailable(feature, now);
        }
        switch (decision) {
            case ALLOWED -> {
                return;
            }
            case DUPLICATE -> {
                // Another instance charged the same request while this one was in flight.
                refundBurstWindows(userId, feature, now);
                return;
            }
            case MONTHLY_LIMIT, WEEKLY_LIMIT -> {
                refundBurstWindows(userId, feature, now);
                QuotaPeriods.Kind kind = decision == QuotaDecision.MONTHLY_LIMIT
                        ? QuotaPeriods.Kind.MONTH : QuotaPeriods.Kind.WEEK;
                throw new QuotaExceededException(feature, decision, QuotaPeriods.resetAt(kind, now));
            }
            default -> {
                refundBurstWindows(userId, feature, now);
                throw new IllegalStateException("额度准入返回了未知判定: " + decision);
            }
        }
    }

    public UsageSnapshot snapshot(UUID userId) {
        Instant now = clock.instant();
        MembershipTier tier = memberships.tier(userId);
        Map<UsageFeature, QuotaCounterStore.PeriodUsage> used = counters.usage(userId, now);
        List<FeatureUsage> features = policies.list().stream()
                .map(policy -> {
                    QuotaCounterStore.PeriodUsage period = used.getOrDefault(policy.feature(),
                            new QuotaCounterStore.PeriodUsage(0, 0));
                    return new FeatureUsage(policy.feature(), period.month(), policy.monthlyLimit(tier),
                            period.week(), policy.weeklyLimit(tier));
                })
                .toList();
        long tokenLimit = plans.tokenLimit(tier);
        List<PlanView> available = plans.list().stream().map(QuotaService::planView).toList();
        List<PackView> availablePacks = packs.list().stream().map(QuotaService::packView).toList();
        return new UsageSnapshot(tier, planView(plans.get(tier)),
                QuotaPeriods.resetAt(QuotaPeriods.Kind.MONTH, now), features,
                new TokenUsage(tokenStore.usedThisMonth(userId, now), tokenLimit, packs.balance(userId)),
                available, availablePacks,
                tier.paid() ? memberships.expiry(userId) : null);
    }

    /**
     * The token budget is a pre-check against settled usage; a call already in flight is not yet
     * visible here, so concurrent requests can overshoot the budget by roughly what they reserve.
     */
    private void guardTokenBudget(UUID userId, UsageFeature feature, MembershipTier tier, Instant now) {
        long limit = plans.tokenLimit(tier);
        if (limit <= 0) return;
        if (tokenStore.usedThisMonth(userId, now) < limit) return;
        if (packs.balance(userId) > 0) return;
        throw new QuotaExceededException(feature, QuotaDecision.TOKEN_LIMIT,
                QuotaPeriods.resetAt(QuotaPeriods.Kind.MONTH, now));
    }

    private void chargeBurstWindows(UUID userId, UsageFeature feature, UsagePolicy policy, Instant now) {
        Outcome userMinute = burst.consumeUserMinute(userId, feature, policy.userMinuteLimit(), now);
        if (userMinute == Outcome.DENIED) {
            throw new QuotaExceededException(feature, QuotaDecision.USER_MINUTE_LIMIT, QuotaPeriods.nextMinute(now));
        }
        if (userMinute == Outcome.UNAVAILABLE) throw guardUnavailable(feature, now);
        Outcome globalMinute = burst.consumeGlobalMinute(feature, policy.globalMinuteLimit(), now);
        if (globalMinute == Outcome.DENIED) {
            burst.refundUserMinute(userId, feature, now);
            throw new QuotaExceededException(feature, QuotaDecision.GLOBAL_MINUTE_LIMIT, QuotaPeriods.nextMinute(now));
        }
        if (globalMinute == Outcome.UNAVAILABLE) {
            burst.refundUserMinute(userId, feature, now);
            throw guardUnavailable(feature, now);
        }
    }

    private void refundBurstWindows(UUID userId, UsageFeature feature, Instant now) {
        burst.refundUserMinute(userId, feature, now);
        burst.refundGlobalMinute(feature, now);
    }

    private QuotaExceededException guardUnavailable(UsageFeature feature, Instant now) {
        return new QuotaExceededException(feature, QuotaDecision.GUARD_UNAVAILABLE, QuotaPeriods.nextMinute(now));
    }

    private static PlanView planView(MembershipPlanService.Plan plan) {
        return new PlanView(plan.code().name(), plan.nameZh(), plan.nameEn(), plan.priceCents(),
                plan.currency(), plan.billingInterval(), plan.tokenMonthlyLimit(), plan.paymentEnabled());
    }

    private static PackView packView(UsagePackService.Pack pack) {
        return new PackView(pack.code(), pack.nameZh(), pack.nameEn(), pack.tokenAmount(), pack.priceCents(),
                pack.currency());
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
