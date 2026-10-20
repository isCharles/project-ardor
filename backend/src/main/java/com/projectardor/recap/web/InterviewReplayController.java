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

import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.recap.service.InterviewReplayService;
import com.projectardor.platform.usage.QuotaProtected;
import com.projectardor.platform.usage.UsageFeature;
import com.projectardor.platform.jobs.aijobs.AiJobService;
import com.projectardor.platform.jobs.aijobs.AiJobResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/interview-replays/questions/{questionId}")
public class InterviewReplayController {
    private final InterviewReplayService service;
    private final AiJobService jobs;

    public InterviewReplayController(InterviewReplayService service, AiJobService jobs) { this.service = service; this.jobs = jobs; }

    @PostMapping("/jobs")
    @QuotaProtected(value = UsageFeature.INTERVIEW_REPLAY, idempotentRequest = true)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiJobResponse queue(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID questionId,
            @Valid @RequestBody InterviewReplaySubmitRequest request) {
        return jobs.evaluateReplay(principal.userId(), questionId, request.command());
    }

    @GetMapping
    public InterviewReplayResponse view(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID questionId) {
        return InterviewReplayResponse.from(service.view(principal.userId(), questionId));
    }

    @PostMapping
    @QuotaProtected(UsageFeature.INTERVIEW_REPLAY)
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewReplayResponse.Attempt submit(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID questionId, @Valid @RequestBody InterviewReplaySubmitRequest request) {
        return InterviewReplayResponse.Attempt.from(service.submit(
                principal.userId(), questionId, request.requestId(), request.answer(), request.retestTaskId()));
    }
}
