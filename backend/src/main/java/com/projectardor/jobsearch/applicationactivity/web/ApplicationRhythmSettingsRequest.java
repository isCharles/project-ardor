package com.projectardor.jobsearch.applicationactivity.web;

import java.time.LocalTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ApplicationRhythmSettingsRequest(
        @NotNull @Min(0) @Max(500) Integer weeklyResumeGoal,
        @Min(0) @Max(500) Integer weeklyGreetingGoal,
        @NotNull Boolean reminderEnabled,
        @NotNull LocalTime reminderTime,
        @NotNull Boolean weekdaysOnly) {}
