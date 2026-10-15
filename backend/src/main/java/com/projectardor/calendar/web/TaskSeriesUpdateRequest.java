package com.projectardor.calendar.web;

import java.time.LocalTime;
import jakarta.validation.constraints.Size;
import com.projectardor.calendar.domain.CalendarTaskPriority;

public record TaskSeriesUpdateRequest(@Size(max=240) String title, @Size(max=4000) String description,
        CalendarTaskPriority priority, LocalTime timeOfDay) {}
