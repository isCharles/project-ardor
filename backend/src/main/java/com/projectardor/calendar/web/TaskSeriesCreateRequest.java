package com.projectardor.calendar.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.projectardor.calendar.domain.CalendarTaskPriority;
import com.projectardor.calendar.domain.RecurrenceFrequency;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskSeriesCreateRequest(
        @NotBlank @Size(max = 240) String title,
        @Size(max = 4000) String description,
        @NotNull RecurrenceFrequency frequency,
        @Min(1) @Max(52) Integer interval,
        /** Weekly only: MON…SUN. Empty means the weekday the series starts on. */
        List<String> weekdays,
        /** Monthly only. Null means the day of month the series starts on. */
        @Min(1) @Max(31) Integer monthDay,
        LocalTime timeOfDay,
        LocalDate startDate,
        LocalDate untilDate,
        @Min(1) @Max(500) Integer occurrenceLimit,
        CalendarTaskPriority priority) {
}
