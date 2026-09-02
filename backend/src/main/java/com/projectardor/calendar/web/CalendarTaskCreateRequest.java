package com.projectardor.calendar.web;

import java.time.Instant;

import com.projectardor.calendar.domain.CalendarTaskPriority;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CalendarTaskCreateRequest(
        @NotBlank @Size(max = 240) String title,
        @Size(max = 4000) String description,
        Instant dueAt,
        CalendarTaskPriority priority) {
}
