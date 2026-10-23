package com.projectardor.scheduling.advice.service;

import java.time.Instant;
import java.util.List;

/** Immutable owned application result, shared by manual and assistant adapters. */
public record CalendarAdviceView(String headline, List<String> days, List<String> weeks,
        List<String> months, Instant updatedAt) {
    public CalendarAdviceView {
        days = List.copyOf(days);
        weeks = List.copyOf(weeks);
        months = List.copyOf(months);
    }
    public static CalendarAdviceView empty() {
        return new CalendarAdviceView(null, List.of(), List.of(), List.of(), null);
    }
}
