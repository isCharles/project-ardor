package com.projectardor.jobsearch.interview.domain;

/**
 * The four states a real interview process can be in. Anything finer-grained
 * lives in the rounds table (round number, stage, time, duration).
 */
public enum OpportunityStatus {
    /** Interview is booked (or the invite is in hand and the date is being arranged). */
    SCHEDULED,
    /** Interview happened; waiting to hear back. */
    AWAITING_RESULT,
    /** Passed this round; the next round is being arranged. */
    NEXT_ROUND,
    /** Process ended without an offer. */
    REJECTED;

    public boolean isTerminal() {
        return this == REJECTED;
    }
}
