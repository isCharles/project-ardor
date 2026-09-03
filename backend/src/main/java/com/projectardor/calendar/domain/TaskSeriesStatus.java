package com.projectardor.calendar.domain;

public enum TaskSeriesStatus {
    /** Still producing occurrences. */
    ACTIVE,
    /** Ran to its end date or occurrence count; kept for history. */
    ENDED,
    /** Stopped by the user before its natural end. */
    CANCELLED
}
