package com.projectardor.opportunity.domain;

/** Lifecycle of a real interview process. Terminal states end the process. */
public enum OpportunityStatus {
    INVITED,
    SCHEDULED,
    INTERVIEWED,
    AWAITING_RESULT,
    OFFER,
    REJECTED,
    WITHDRAWN,
    NO_RESPONSE;

    public boolean isTerminal() {
        return this == OFFER || this == REJECTED || this == WITHDRAWN || this == NO_RESPONSE;
    }
}
