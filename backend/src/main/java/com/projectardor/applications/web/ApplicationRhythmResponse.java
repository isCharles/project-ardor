package com.projectardor.applications.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

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
}
