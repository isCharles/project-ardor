package com.projectardor.scheduling.advice.web;

import java.time.Instant;
import java.util.List;

public record CalendarAdviceResponse(
        String headline,
        List<String> days,
        List<String> weeks,
        List<String> months,
        Instant updatedAt) {
    public static CalendarAdviceResponse empty() {
        return new CalendarAdviceResponse(null, List.of(), List.of(), List.of(), null);
    }

    public record Update(String headline, List<String> days, List<String> weeks, List<String> months) {}
}
