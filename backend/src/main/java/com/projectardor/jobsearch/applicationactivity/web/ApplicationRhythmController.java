package com.projectardor.jobsearch.applicationactivity.web;

import java.time.LocalDate;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.jobsearch.applicationactivity.service.ApplicationRhythmService;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/applications/rhythm")
public class ApplicationRhythmController {
    private final ApplicationRhythmService service;

    public ApplicationRhythmController(ApplicationRhythmService service) {
        this.service = service;
    }

    @GetMapping
    public ApplicationRhythmResponse get(@AuthenticationPrincipal ArdorPrincipal principal) {
        return ApplicationRhythmResponse.from(service.get(principal.userId()));
    }

    @PutMapping("/settings")
    public ApplicationRhythmResponse settings(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody ApplicationRhythmSettingsRequest request) {
        return ApplicationRhythmResponse.from(service.configure(principal.userId(), request.weeklyGoal(), request.reminderEnabled(),
                request.reminderTime(), request.weekdaysOnly()));
    }

    @PutMapping("/days/{date}")
    public ApplicationRhythmResponse record(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable LocalDate date,
            @Valid @RequestBody ApplicationCountRequest request) {
        return ApplicationRhythmResponse.from(service.record(principal.userId(), date, request.count()));
    }

    @PostMapping("/today/increment")
    public ApplicationRhythmResponse increment(@AuthenticationPrincipal ArdorPrincipal principal) {
        return ApplicationRhythmResponse.from(service.incrementToday(principal.userId()));
    }

    @PostMapping("/reminder/dismiss")
    public ApplicationRhythmResponse dismiss(@AuthenticationPrincipal ArdorPrincipal principal) {
        return ApplicationRhythmResponse.from(service.dismissToday(principal.userId()));
    }
}
