package com.projectardor.scheduling.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.scheduling.domain.CalendarTask;
import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.domain.CalendarTaskStatus;
import com.projectardor.scheduling.domain.CalendarTaskKind;
import com.projectardor.scheduling.service.CalendarTaskView;

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
        return from(CalendarTaskView.from(task));
    }

    public static CalendarTaskResponse from(CalendarTaskView task) {
        return new CalendarTaskResponse(
                task.id(), task.title(), task.description(), task.status(),
                task.priority(), task.source(), task.taskKind(), task.actionPath(),
                task.seriesId(), task.jobOpportunityId(), task.dueAt(), task.completedAt(),
                task.createdAt(), task.updatedAt());
    }
}
