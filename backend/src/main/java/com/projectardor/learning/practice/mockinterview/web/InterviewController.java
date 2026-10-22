package com.projectardor.learning.practice.mockinterview.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.learning.practice.mockinterview.service.InterviewService;
import com.projectardor.platform.usage.QuotaProtected;
import com.projectardor.platform.usage.UsageFeature;
import com.projectardor.platform.jobs.aijobs.AiJobService;
import com.projectardor.platform.jobs.aijobs.AiJobResponse;
import com.projectardor.platform.jobs.aijobs.AiJobRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    private final InterviewService interviewService;
    private final AiJobService jobs;

    public InterviewController(InterviewService interviewService, AiJobService jobs) {
        this.interviewService = interviewService;
        this.jobs = jobs;
    }

    @PostMapping
    @QuotaProtected(UsageFeature.INTERVIEW_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewSessionResponse create(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody CreateInterviewRequest request) {
        return InterviewSessionResponse.fromView(interviewService.createView(
                principal.userId(), request.resumeAnalysisId(), request.resolvedModality(), request.targetCompany(),
                request.targetRole(), request.resolvedQuestionCount(), request.jobOpportunityId()));
    }

    @GetMapping
    public List<InterviewSessionResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return interviewService.listViews(principal.userId()).stream()
                .map(InterviewSessionResponse::fromView)
                .toList();
    }

    @GetMapping("/{sessionId}")
    public InterviewSessionResponse get(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return InterviewSessionResponse.fromView(interviewService.getView(principal.userId(), sessionId));
    }

    @GetMapping("/{sessionId}/next-question")
    public InterviewProgressResponse getNextQuestion(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return InterviewProgressResponse.fromView(
                interviewService.nextQuestionView(principal.userId(), sessionId));
    }

    @PostMapping("/{sessionId}/answers")
    public InterviewProgressResponse submitAnswer(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId,
            @Valid @RequestBody SubmitAnswerRequest request) {
        return InterviewProgressResponse.fromView(interviewService.submitAnswerView(
                principal.userId(), sessionId, request.questionId(),
                request.answerText(), request.durationSeconds()));
    }

    @PostMapping("/{sessionId}/finish")
    @QuotaProtected(UsageFeature.INTERVIEW_EVALUATION)
    public InterviewEvaluationResponse finish(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return InterviewEvaluationResponse.fromView(interviewService.finishView(principal.userId(), sessionId));
    }

    @PostMapping("/{sessionId}/evaluation-jobs")
    @QuotaProtected(value = UsageFeature.INTERVIEW_EVALUATION, idempotentRequest = true)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiJobResponse queueEvaluation(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId, @Valid @RequestBody AiJobRequest request) {
        return jobs.evaluateInterview(principal.userId(), sessionId, request.requestId());
    }

    @PostMapping("/{sessionId}/cancel")
    public InterviewSessionResponse cancel(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return InterviewSessionResponse.fromView(interviewService.cancelView(principal.userId(), sessionId));
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        interviewService.delete(principal.userId(), sessionId);
    }

    @GetMapping("/{sessionId}/evaluation")
    public InterviewEvaluationResponse getEvaluation(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return InterviewEvaluationResponse.fromView(
                interviewService.evaluationView(principal.userId(), sessionId));
    }
}
