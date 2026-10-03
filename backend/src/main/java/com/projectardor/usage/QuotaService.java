package com.projectardor.usage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class QuotaService {
    private final UsagePolicyRepository policies;
    private final MembershipService memberships;
    private final QuotaStore store;
    private final Clock clock;

    @Autowired
    public QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaStore store) {
        this(policies, memberships, store, Clock.systemUTC());
    }

    QuotaService(UsagePolicyRepository policies, MembershipService memberships, QuotaStore store, Clock clock) {
        this.policies = policies;
        this.memberships = memberships;
        this.store = store;
        this.clock = clock;
    }

    public void consume(UUID userId, UsageFeature feature, UUID requestId) {
        Instant now = clock.instant();
        UsagePolicy policy = policies.get(feature);
        MembershipTier tier = memberships.tier(userId);
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
        return new UsageSnapshot(tier, nextMonth(now), features);
    }

    private Instant nextMonth(Instant now) {
        return LocalDate.of(now.atZone(ZoneOffset.UTC).getYear(),
                now.atZone(ZoneOffset.UTC).getMonthValue(), 1)
                .plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public record FeatureUsage(UsageFeature feature, int used, int limit) {}
    public record UsageSnapshot(MembershipTier tier, Instant resetsAt, List<FeatureUsage> features) {}
}
