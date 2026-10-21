package com.projectardor.scheduling.service;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.scheduling.domain.CalendarTask;
import com.projectardor.scheduling.domain.CalendarTaskKind;
import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.domain.CalendarTaskStatus;

/** Immutable scheduling result shared by HTTP and assistant adapters. */
public record CalendarTaskView(
        UUID id, String title, String description, CalendarTaskStatus status,
        CalendarTaskPriority priority, CalendarTaskSource source, CalendarTaskKind taskKind,
        String actionPath, UUID seriesId, UUID jobOpportunityId, Instant dueAt,
        Instant completedAt, Instant createdAt, Instant updatedAt) {
    public static CalendarTaskView from(CalendarTask task) {
        return new CalendarTaskView(task.getId(), task.getTitle(), task.getDescription(), task.getStatus(),
                task.getPriority(), task.getSource(), task.getTaskKind(), task.getActionPath(),
                task.getSeriesId(), task.getJobOpportunityId(), task.getDueAt(), task.getCompletedAt(),
                task.getCreatedAt(), task.getUpdatedAt());
    }
}
