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
import com.projectardor.recap.service.InterviewReplayService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/interview-replays/questions/{questionId}")
public class InterviewReplayController {
    private final InterviewReplayService service;

    public InterviewReplayController(InterviewReplayService service) { this.service = service; }

    @GetMapping
    public InterviewReplayResponse view(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID questionId) {
        return service.view(principal.userId(), questionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewReplayResponse.Attempt submit(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID questionId, @Valid @RequestBody InterviewReplaySubmitRequest request) {
        return InterviewReplayResponse.Attempt.from(service.submit(
                principal.userId(), questionId, request.requestId(), request.answer(), request.retestTaskId()));
    }
}
