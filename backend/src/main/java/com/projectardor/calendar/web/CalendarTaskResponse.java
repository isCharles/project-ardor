package com.projectardor.calendar.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.calendar.domain.CalendarTask;
import com.projectardor.calendar.domain.CalendarTaskPriority;
import com.projectardor.calendar.domain.CalendarTaskSource;
import com.projectardor.calendar.domain.CalendarTaskStatus;
import com.projectardor.calendar.domain.CalendarTaskKind;

public record CalendarTaskResponse(
        UUID id,
        String title,
        String description,
        CalendarTaskStatus status,
        CalendarTaskPriority priority,
        CalendarTaskSource source,
        CalendarTaskKind taskKind,
        String actionPath,
        /** Set when this row is one occurrence of a repeating arrangement. */
        UUID seriesId,
        Instant dueAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static CalendarTaskResponse from(CalendarTask task) {
        return new CalendarTaskResponse(
                task.getId(), task.getTitle(), task.getDescription(), task.getStatus(),
                task.getPriority(), task.getSource(), task.getTaskKind(), task.getActionPath(),
                task.getSeriesId(), task.getDueAt(), task.getCompletedAt(),
                task.getCreatedAt(), task.getUpdatedAt());
    }
}
