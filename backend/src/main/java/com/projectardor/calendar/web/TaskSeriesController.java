package com.projectardor.calendar.web;

import java.time.DayOfWeek;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.calendar.domain.CalendarTaskSource;
import com.projectardor.calendar.domain.TaskSeries;
import com.projectardor.calendar.service.TaskSeriesService;

import jakarta.validation.Valid;

/** Repeating arrangements: the rule behind a run of calendar occurrences. */
@RestController
@RequestMapping("/api/calendar/series")
public class TaskSeriesController {

    private static final int UPCOMING_SHOWN = 4;

    private final TaskSeriesService service;

    public TaskSeriesController(TaskSeriesService service) {
        this.service = service;
    }

    @GetMapping
    public List<TaskSeriesResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return service.list(principal.userId()).stream()
                .map(series -> TaskSeriesResponse.from(
                        series, service.upcoming(principal.userId(), series.getId(), UPCOMING_SHOWN)))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskSeriesResponse create(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody TaskSeriesCreateRequest request) {
        TaskSeries series = service.create(
                principal.userId(),
                request.title(),
                request.description(),
                request.priority(),
                CalendarTaskSource.MANUAL,
                request.frequency(),
                request.interval(),
                weekdays(request.weekdays()),
                request.monthDay(),
                request.timeOfDay(),
                request.startDate(),
                request.untilDate(),
                request.occurrenceLimit()).series();
        return TaskSeriesResponse.from(
                series, service.upcoming(principal.userId(), series.getId(), UPCOMING_SHOWN));
    }

    /** Stops the series; past and already-touched occurrences stay on the calendar. */
    @DeleteMapping("/{seriesId}")
    public Map<String, Object> cancel(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID seriesId) {
        return Map.of("cancelled", true, "withdrawnOccurrences",
                service.cancel(principal.userId(), seriesId));
    }

    private Set<DayOfWeek> weekdays(List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<DayOfWeek> days = new LinkedHashSet<>();
        values.forEach(value -> days.add(TaskSeriesService.parseWeekday(value)));
        return days;
    }
}
