package com.projectardor.jobsearch.applicationactivity.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Owned application result; daily counts are not individual job applications. */
public record ApplicationRhythmView(
        LocalDate today, LocalDate weekStart, int todayCount, int weeklyCount, int weeklyGoal,
        boolean reminderEnabled, LocalTime reminderTime, boolean weekdaysOnly, boolean reminderDue,
        List<DayCount> days, String advice) {
    public ApplicationRhythmView {
        days = List.copyOf(days);
    }

    public ApplicationRhythmView(LocalDate today, LocalDate weekStart, int todayCount, int weeklyCount,
            int weeklyGoal, boolean reminderEnabled, LocalTime reminderTime, boolean weekdaysOnly,
            boolean reminderDue, List<DayCount> days) {
        this(today, weekStart, todayCount, weeklyCount, weeklyGoal, reminderEnabled, reminderTime,
                weekdaysOnly, reminderDue, days, null);
    }
    public record DayCount(LocalDate date, int count) {}
}
