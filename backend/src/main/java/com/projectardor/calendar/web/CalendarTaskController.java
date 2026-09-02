package com.projectardor.calendar.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.calendar.domain.CalendarTaskSource;
import com.projectardor.calendar.service.CalendarTaskService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/calendar/tasks")
public class CalendarTaskController {
    private final CalendarTaskService service;

    public CalendarTaskController(CalendarTaskService service) {
        this.service = service;
    }

    @GetMapping
    public List<CalendarTaskResponse> list(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.list(principal.userId(), from, to).stream()
                .map(CalendarTaskResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CalendarTaskResponse create(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody CalendarTaskCreateRequest request) {
        return CalendarTaskResponse.from(service.create(
                principal.userId(), request.title(), request.description(), request.dueAt(),
                request.priority(), CalendarTaskSource.MANUAL));
    }

    @PutMapping("/{taskId}")
    public CalendarTaskResponse update(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID taskId,
            @Valid @RequestBody CalendarTaskUpdateRequest request) {
        return CalendarTaskResponse.from(service.update(
                principal.userId(), taskId, request.title(), request.description(), request.dueAt(),
                request.priority(), request.status()));
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID taskId) {
        service.delete(principal.userId(), taskId);
    }
}
