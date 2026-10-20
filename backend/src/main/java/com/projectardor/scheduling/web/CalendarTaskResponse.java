package com.projectardor.scheduling.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.scheduling.domain.CalendarTask;
import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.domain.CalendarTaskStatus;
import com.projectardor.scheduling.domain.CalendarTaskKind;

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
        /** Set when this task belongs to a real interview process. */
        UUID jobOpportunityId,
        Instant dueAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static CalendarTaskResponse from(CalendarTask task) {
        return new CalendarTaskResponse(
                task.getId(), task.getTitle(), task.getDescription(), task.getStatus(),
                task.getPriority(), task.getSource(), task.getTaskKind(), task.getActionPath(),
                task.getSeriesId(), task.getJobOpportunityId(), task.getDueAt(), task.getCompletedAt(),
                task.getCreatedAt(), task.getUpdatedAt());
    }
}
