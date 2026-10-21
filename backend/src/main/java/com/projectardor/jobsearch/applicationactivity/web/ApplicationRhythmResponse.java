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
        List<DayCount> days,
        String advice) {
    public record DayCount(LocalDate date, int count) {}

    public ApplicationRhythmResponse(LocalDate today, LocalDate weekStart, int todayCount, int weeklyCount,
            int weeklyGoal, boolean reminderEnabled, LocalTime reminderTime, boolean weekdaysOnly,
            boolean reminderDue, List<DayCount> days) {
        this(today, weekStart, todayCount, weeklyCount, weeklyGoal, reminderEnabled, reminderTime,
                weekdaysOnly, reminderDue, days, null);
    }

    public static ApplicationRhythmResponse from(ApplicationRhythmView view) {
        return new ApplicationRhythmResponse(view.today(), view.weekStart(), view.todayCount(), view.weeklyCount(),
                view.weeklyGoal(), view.reminderEnabled(), view.reminderTime(), view.weekdaysOnly(), view.reminderDue(),
                view.days().stream().map(day -> new DayCount(day.date(), day.count())).toList(), view.advice());
    }
}
