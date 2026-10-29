package com.projectardor.jobsearch.applicationactivity.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.projectardor.jobsearch.applicationactivity.service.ApplicationRhythmView;

public record ApplicationRhythmResponse(
        LocalDate today,
        LocalDate weekStart,
        Integer todayGreetingCount,
        Integer todayResumeCount,
        int weeklyGreetingCount,
        int weeklyResumeCount,
        int weeklyGreetingRecordedDays,
        int weeklyResumeRecordedDays,
        int weeklyResumeGoal,
        Integer weeklyGreetingGoal,
        boolean reminderEnabled,
        LocalTime reminderTime,
        boolean weekdaysOnly,
        boolean reminderDue,
        List<DayCount> days,
        String advice) {
    public record DayCount(LocalDate date, Integer greetingCount, Integer resumeCount) {}

    public static ApplicationRhythmResponse from(ApplicationRhythmView view) {
        return new ApplicationRhythmResponse(view.today(), view.weekStart(), view.todayGreetingCount(),
                view.todayResumeCount(), view.weeklyGreetingCount(), view.weeklyResumeCount(),
                view.weeklyGreetingRecordedDays(), view.weeklyResumeRecordedDays(), view.weeklyResumeGoal(),
                view.weeklyGreetingGoal(), view.reminderEnabled(), view.reminderTime(), view.weekdaysOnly(),
                view.reminderDue(), view.days().stream()
                        .map(day -> new DayCount(day.date(), day.greetingCount(), day.resumeCount())).toList(),
                view.advice());
    }
}
