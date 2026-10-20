package com.projectardor.scheduling.web;

import java.time.Instant;

import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CalendarTaskUpdateRequest(
        @NotBlank @Size(max = 240) String title,
        @Size(max = 4000) String description,
        Instant dueAt,
        CalendarTaskPriority priority,
        CalendarTaskStatus status) {
}
