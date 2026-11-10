package com.projectardor.learning.practice.mockinterview.web;

import java.util.List;
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

import com.projectardor.learning.practice.mockinterview.service.adaptive.AdaptiveInterviewOrchestrator;
import com.projectardor.learning.practice.mockinterview.service.adaptive.AdaptiveInterviewState;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.usage.QuotaProtected;
import com.projectardor.platform.usage.UsageFeature;

import jakarta.validation.Valid;

/**
 * The adaptive interview has its own entry point because starting one plans competencies instead of
 * writing a fixed paper, and every later read drives recovery. Cancel, delete and the saved report
 * stay on {@link InterviewController}: those operations are the same for both modes.
 */
@RestController
@RequestMapping("/api/interviews/adaptive")
public class AdaptiveInterviewController {

    private final AdaptiveInterviewOrchestrator orchestrator;

    public AdaptiveInterviewController(AdaptiveInterviewOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    @QuotaProtected(UsageFeature.INTERVIEW_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewSessionResponse create(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody CreateAdaptiveInterviewRequest request) {
        return InterviewSessionResponse.from(orchestrator.start(principal.userId(), request.resumeAnalysisId(),
                request.targetCompany(), request.targetRole(), request.jobOpportunityId(), request.seniority(),
                request.durationMinutes(), request.deepDive(), request.jobDescription()));
    }

    @GetMapping
    public List<InterviewSessionResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return orchestrator.list(principal.userId()).stream()
                .map(InterviewSessionResponse::fromView)
                .toList();
    }

    @GetMapping("/{sessionId}")
    public AdaptiveInterviewState state(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return orchestrator.state(principal.userId(), sessionId);
    }

    @PostMapping("/{sessionId}/answers")
    public AdaptiveInterviewState submitAnswer(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId,
            @Valid @RequestBody SubmitAdaptiveAnswerRequest request) {
        return orchestrator.submitAnswer(principal.userId(), sessionId, request.questionId(),
                request.answerText(), request.durationSeconds(), request.difficultyFeedback());
    }

    @PostMapping("/{sessionId}/finish")
    @QuotaProtected(UsageFeature.INTERVIEW_EVALUATION)
    public InterviewEvaluationResponse finish(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return InterviewEvaluationResponse.from(orchestrator.finish(principal.userId(), sessionId));
    }
}