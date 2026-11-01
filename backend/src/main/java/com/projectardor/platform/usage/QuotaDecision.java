package com.projectardor.platform.usage;

/** What an admission attempt decided. Only ALLOWED and DUPLICATE let the request proceed. */
public enum QuotaDecision {
    ALLOWED,
    DUPLICATE,
    MONTHLY_LIMIT,
    WEEKLY_LIMIT,
    USER_MINUTE_LIMIT,
    GLOBAL_MINUTE_LIMIT,
    TOKEN_LIMIT,
    /** A guard the quota depends on could not answer, so the request was refused rather than waved through. */
    GUARD_UNAVAILABLE
}
