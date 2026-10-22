package com.projectardor.platform.usage;

/** Subscription plan. Tokens are the primary monthly budget; module counts remain a safety rail. */
public enum MembershipTier {
    FREE(0),
    PLUS(1),
    PRO(2);

    private final int rank;

    MembershipTier(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(MembershipTier other) {
        return rank >= other.rank;
    }

    public MembershipTier max(MembershipTier other) {
        return atLeast(other) ? this : other;
    }

    public boolean paid() {
        return this != FREE;
    }
}
