package com.projectardor.jobsearch.applicationactivity.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Owned application activity snapshot. Greetings and actual resume sends are separate totals;
 * a null count means unrecorded, not zero. Legacy days only carry greetings.
 */
public record ApplicationRhythmView(
        LocalDate today, LocalDate weekStart,
        Integer todayGreetingCount, Integer todayResumeCount,
        int weeklyGreetingCount, int weeklyResumeCount,
        int weeklyGreetingRecordedDays, int weeklyResumeRecordedDays,
        int weeklyResumeGoal, Integer weeklyGreetingGoal,
        boolean reminderEnabled, LocalTime reminderTime, boolean weekdaysOnly, boolean reminderDue,
        List<DayCount> days, String advice) {
    public ApplicationRhythmView {
        days = List.copyOf(days);
    }

    public record DayCount(LocalDate date, Integer greetingCount, Integer resumeCount) {}
}
