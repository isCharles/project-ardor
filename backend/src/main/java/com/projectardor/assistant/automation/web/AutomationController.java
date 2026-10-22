package com.projectardor.assistant.automation.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.assistant.automation.service.AutomationScheduler;
import com.projectardor.assistant.automation.service.AutomationService;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/automations")
public class AutomationController {
    private final AutomationService service;
    private final AutomationScheduler scheduler;

    public AutomationController(AutomationService service, AutomationScheduler scheduler) {
        this.service = service;
        this.scheduler = scheduler;
    }

    @GetMapping
    public List<AutomationResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return service.list(principal.userId()).stream().map(AutomationResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AutomationResponse get(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        return AutomationResponse.from(service.get(principal.userId(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AutomationResponse create(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody AutomationCreateRequest request) {
        return AutomationResponse.from(service.create(principal.userId(), request.title(), request.prompt(),
                request.cronExpression(), request.timezone()));
    }

    @PatchMapping("/{id}")
    public AutomationResponse update(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody AutomationUpdateRequest request) {
        return AutomationResponse.from(service.update(principal.userId(), id, request.title(), request.prompt(),
                request.cronExpression(), request.timezone(), request.enabled()));
    }

    @PostMapping("/{id}/run")
    public AutomationScheduler.RunResult runNow(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        return scheduler.runNow(principal.userId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        service.delete(principal.userId(), id);
    }
}
