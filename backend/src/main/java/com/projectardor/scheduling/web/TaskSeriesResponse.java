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
        return new TaskSeriesResponse(
                series.getId(),
                series.getTitle(),
                series.getDescription(),
                series.summary(),
                series.getPriority(),
                series.getSource(),
                series.getTimezone(),
                series.getFrequency(),
                series.getIntervalValue(),
                series.getFrequency() == RecurrenceFrequency.WEEKLY
                        ? series.weekdays().stream().sorted().map(Enum::name).toList()
                        : List.of(),
                series.getByMonthDay(),
                series.getTimeOfDay(),
                series.getStartDate(),
                series.getUntilDate(),
                series.getOccurrenceLimit(),
                series.getStatus(),
                series.getMaterializedThrough(),
                upcoming == null ? List.of() : upcoming,
                series.getCreatedAt());
    }
}
