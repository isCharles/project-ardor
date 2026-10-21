package com.projectardor.scheduling.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.domain.RecurrenceFrequency;
import com.projectardor.scheduling.domain.TaskSeries;
import com.projectardor.scheduling.domain.TaskSeriesStatus;
import com.projectardor.scheduling.service.TaskSeriesView;

public record TaskSeriesResponse(
        UUID id,
        String title,
        String description,
        /** One readable line: 每周四 19:00. */
        String summary,
        CalendarTaskPriority priority,
        CalendarTaskSource source,
        String timezone,
        RecurrenceFrequency frequency,
        int interval,
        List<String> weekdays,
        Integer monthDay,
        LocalTime timeOfDay,
        LocalDate startDate,
        LocalDate untilDate,
        Integer occurrenceLimit,
        TaskSeriesStatus status,
        LocalDate materializedThrough,
        List<LocalDate> upcoming,
        Instant createdAt) {

    public static TaskSeriesResponse from(TaskSeries series, List<LocalDate> upcoming) {
        return from(TaskSeriesView.from(series, upcoming));
    }

    public static TaskSeriesResponse from(TaskSeriesView series) {
        return new TaskSeriesResponse(
                series.id(), series.title(), series.description(), series.summary(), series.priority(),
                series.source(), series.timezone(), series.frequency(), series.interval(), series.weekdays(),
                series.monthDay(), series.timeOfDay(), series.startDate(), series.untilDate(), series.occurrenceLimit(),
                series.status(), series.materializedThrough(), series.upcoming(), series.createdAt());
    }
}
