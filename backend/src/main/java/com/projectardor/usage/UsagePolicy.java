package com.projectardor.usage;

import java.time.Instant;

public record UsagePolicy(UsageFeature feature, int freeMonthlyLimit, int memberMonthlyLimit,
        int userMinuteLimit, int globalMinuteLimit, Instant updatedAt) {
    public UsagePolicy {
        validateLimits(freeMonthlyLimit, memberMonthlyLimit, userMinuteLimit, globalMinuteLimit);
    }

    static void validateLimits(int freeMonthlyLimit, int memberMonthlyLimit,
            int userMinuteLimit, int globalMinuteLimit) {
        if (freeMonthlyLimit < 0 || memberMonthlyLimit < 0
                || userMinuteLimit <= 0 || globalMinuteLimit <= 0) {
            throw new IllegalArgumentException("额度不能为负，短时限额必须大于零");
        }
    }

    public int monthlyLimit(MembershipTier tier) {
        return tier == MembershipTier.MEMBER ? memberMonthlyLimit : freeMonthlyLimit;
    }
}
