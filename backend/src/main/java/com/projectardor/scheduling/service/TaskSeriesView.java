package com.projectardor.scheduling.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.domain.RecurrenceFrequency;
import com.projectardor.scheduling.domain.TaskSeries;
import com.projectardor.scheduling.domain.TaskSeriesStatus;

/** Scheduling-owned recurrence snapshot; dates refer to materialized calendar rows. */
public record TaskSeriesView(
        UUID id, String title, String description, String summary,
        CalendarTaskPriority priority, CalendarTaskSource source, String timezone,
        RecurrenceFrequency frequency, int interval, List<String> weekdays, Integer monthDay,
        LocalTime timeOfDay, LocalDate startDate, LocalDate untilDate, Integer occurrenceLimit,
        TaskSeriesStatus status, LocalDate materializedThrough, List<LocalDate> upcoming, Instant createdAt) {
    public TaskSeriesView {
        weekdays = Collections.unmodifiableList(new ArrayList<>(weekdays));
        upcoming = upcoming == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(upcoming));
    }

    public static TaskSeriesView from(TaskSeries series, List<LocalDate> upcoming) {
        return new TaskSeriesView(series.getId(), series.getTitle(), series.getDescription(), series.summary(),
                series.getPriority(), series.getSource(), series.getTimezone(), series.getFrequency(),
                series.getIntervalValue(), series.getFrequency() == RecurrenceFrequency.WEEKLY
                        ? series.weekdays().stream().sorted().map(Enum::name).toList() : List.of(),
                series.getByMonthDay(), series.getTimeOfDay(), series.getStartDate(), series.getUntilDate(),
                series.getOccurrenceLimit(), series.getStatus(), series.getMaterializedThrough(), upcoming, series.getCreatedAt());
    }
}
