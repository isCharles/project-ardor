package com.projectardor.recap.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.recap.service.InterviewRetestService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/interview-replays/questions/{questionId}/retest")
public class InterviewRetestController {
    private final InterviewRetestService service;

    public InterviewRetestController(InterviewRetestService service) { this.service = service; }

    @GetMapping
    public InterviewRetestResponse.Overview active(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID questionId) {
        return new InterviewRetestResponse.Overview(service.active(principal.userId(), questionId)
                .map(InterviewRetestResponse::from).orElse(null));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewRetestResponse schedule(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID questionId, @Valid @RequestBody InterviewRetestRequest request) {
        return InterviewRetestResponse.from(service.schedule(principal.userId(), questionId,
                request.attemptId(), request.dueAt()));
    }
}
