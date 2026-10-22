package com.projectardor.platform.usage;

import java.time.Instant;

public record UsagePolicy(UsageFeature feature, int freeMonthlyLimit, int memberMonthlyLimit,
        int plusMonthlyLimit, int proMonthlyLimit, int userMinuteLimit, int globalMinuteLimit,
        Instant updatedAt) {

    public UsagePolicy {
        validateLimits(freeMonthlyLimit, memberMonthlyLimit, plusMonthlyLimit, proMonthlyLimit,
                userMinuteLimit, globalMinuteLimit);
    }

    /** Compatibility for callers that predate the Free/Plus/Pro split. */
    public UsagePolicy(UsageFeature feature, int freeMonthlyLimit, int memberMonthlyLimit,
            int userMinuteLimit, int globalMinuteLimit, Instant updatedAt) {
        this(feature, freeMonthlyLimit, memberMonthlyLimit, memberMonthlyLimit,
                Math.max(memberMonthlyLimit, memberMonthlyLimit * 4), userMinuteLimit, globalMinuteLimit, updatedAt);
    }

    static void validateLimits(int freeMonthlyLimit, int memberMonthlyLimit, int plusMonthlyLimit,
            int proMonthlyLimit, int userMinuteLimit, int globalMinuteLimit) {
        if (freeMonthlyLimit < 0 || memberMonthlyLimit < 0 || plusMonthlyLimit < 0 || proMonthlyLimit < 0
                || userMinuteLimit <= 0 || globalMinuteLimit <= 0) {
            throw new IllegalArgumentException("额度不能为负，短时限额必须大于零");
        }
    }

    public int monthlyLimit(MembershipTier tier) {
        return switch (tier) {
            case PLUS -> plusMonthlyLimit;
            case PRO -> proMonthlyLimit;
            case FREE -> freeMonthlyLimit;
        };
    }
}
