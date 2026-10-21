package com.projectardor.jobsearch.applicationactivity.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import com.projectardor.jobsearch.applicationactivity.service.ApplicationRhythmView;

public record ApplicationRhythmResponse(
        LocalDate today,
        LocalDate weekStart,
        int todayCount,
        int weeklyCount,
        int weeklyGoal,
        boolean reminderEnabled,
        LocalTime reminderTime,
        boolean weekdaysOnly,
        boolean reminderDue,
        List<DayCount> days) {
    public record DayCount(LocalDate date, int count) {}
    public static ApplicationRhythmResponse from(ApplicationRhythmView view) {
        return new ApplicationRhythmResponse(view.today(), view.weekStart(), view.todayCount(), view.weeklyCount(),
                view.weeklyGoal(), view.reminderEnabled(), view.reminderTime(), view.weekdaysOnly(), view.reminderDue(),
                view.days().stream().map(day -> new DayCount(day.date(), day.count())).toList());
    }
}
