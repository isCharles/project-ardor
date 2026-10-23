package com.projectardor.scheduling.advice.web;

import java.time.Instant;
import java.util.List;
import com.projectardor.scheduling.advice.service.CalendarAdviceView;

public record CalendarAdviceResponse(
        String headline,
        List<String> days,
        List<String> weeks,
        List<String> months,
        Instant updatedAt) {
    public static CalendarAdviceResponse empty() {
        return from(CalendarAdviceView.empty());
    }

    public static CalendarAdviceResponse from(CalendarAdviceView view) {
        return new CalendarAdviceResponse(view.headline(), view.days(), view.weeks(), view.months(), view.updatedAt());
    }

    public record Update(String headline, List<String> days, List<String> weeks, List<String> months) {}
}
