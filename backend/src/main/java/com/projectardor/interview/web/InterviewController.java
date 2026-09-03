package com.projectardor.interview.web;

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

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.interview.service.InterviewService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewSessionResponse create(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody CreateInterviewRequest request) {
        return InterviewSessionResponse.from(interviewService.create(
                principal.userId(), request.resumeAnalysisId(), request.targetCompany(),
                request.targetRole(), request.resolvedQuestionCount()));
    }

    @GetMapping
    public List<InterviewSessionResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return interviewService.list(principal.userId()).stream()
                .map(InterviewSessionResponse::from)
                .toList();
    }

    @GetMapping("/{sessionId}")
    public InterviewSessionResponse get(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return InterviewSessionResponse.from(interviewService.get(principal.userId(), sessionId));
    }

    @GetMapping("/{sessionId}/next-question")
    public InterviewProgressResponse getNextQuestion(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return InterviewProgressResponse.from(
                interviewService.getNextQuestion(principal.userId(), sessionId));
    }

    @PostMapping("/{sessionId}/answers")
    public InterviewProgressResponse submitAnswer(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId,
            @Valid @RequestBody SubmitAnswerRequest request) {
        return InterviewProgressResponse.from(interviewService.submitAnswer(
                principal.userId(), sessionId, request.questionId(),
                request.answerText(), request.durationSeconds()));
    }

    @PostMapping("/{sessionId}/finish")
    public InterviewEvaluationResponse finish(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return InterviewEvaluationResponse.from(interviewService.finish(principal.userId(), sessionId));
    }

    @PostMapping("/{sessionId}/cancel")
    public InterviewSessionResponse cancel(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId) {
        return InterviewSessionResponse.from(interviewService.cancel(principal.userId(), sessionId));
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
        return InterviewEvaluationResponse.from(
                interviewService.getEvaluation(principal.userId(), sessionId));
    }
}
