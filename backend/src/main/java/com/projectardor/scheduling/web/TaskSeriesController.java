package com.projectardor.scheduling.web;

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

import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.service.TaskSeriesService;

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
        return service.listViews(principal.userId(), UPCOMING_SHOWN).stream()
                .map(TaskSeriesResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskSeriesResponse create(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody TaskSeriesCreateRequest request) {
        var series = service.createView(
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
                request.occurrenceLimit(), UPCOMING_SHOWN);
        return TaskSeriesResponse.from(series);
    }

    /** Updates supported fields while retaining historical and individually edited occurrences. */
    @org.springframework.web.bind.annotation.PatchMapping("/{seriesId}")
    public Map<String, Object> update(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID seriesId,
            @Valid @RequestBody TaskSeriesUpdateRequest request) {
        var result = service.updateDetailsView(principal.userId(), seriesId, request.title(), request.description(), request.priority(), request.timeOfDay(), UPCOMING_SHOWN);
        return Map.of("series", TaskSeriesResponse.from(result.series()),
                "updatedOccurrences", result.updatedOccurrences(), "preservedOccurrences", result.preservedOccurrences());
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
