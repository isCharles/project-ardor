package com.projectardor.learning.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.learning.service.LearningPlanService;
import com.projectardor.learning.service.LearningPlanCreationStore;
import com.projectardor.usage.QuotaProtected;
import com.projectardor.usage.UsageFeature;
import com.projectardor.aijobs.AiJobService;
import com.projectardor.aijobs.AiJobResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/learning-plans")
public class LearningPlanController {
    private final LearningPlanService service;
    private final AiJobService jobs;

    public LearningPlanController(LearningPlanService service, AiJobService jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    @GetMapping
    public List<LearningPlanResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return service.list(principal.userId()).stream().map(LearningPlanResponse::from).toList();
    }

    @GetMapping("/request-status")
    public List<LearningPlanCreationStore.RequestStatus> requestStatus(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam List<UUID> requestIds) {
        return service.requestStatuses(principal.userId(), requestIds);
    }

    @GetMapping("/{id}")
    public LearningPlanResponse get(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        return LearningPlanResponse.from(service.get(principal.userId(), id));
    }

    @PostMapping
    @QuotaProtected(value = UsageFeature.LEARNING_PLAN, idempotentRequest = true, optionalRequestId = true)
    @ResponseStatus(HttpStatus.CREATED)
    public LearningPlanResponse create(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody LearningPlanCreateRequest request) {
        return LearningPlanResponse.from(service.create(principal.userId(), request.requestId(), request.concept(),
                request.reason(), request.sourceType(), request.sourceId(), request.scheduledAt(), request.context()));
    }

    @PostMapping("/jobs")
    @QuotaProtected(value = UsageFeature.LEARNING_PLAN, idempotentRequest = true, optionalRequestId = true)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiJobResponse queueCreate(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody LearningPlanCreateRequest request) {
        return jobs.createLearning(principal.userId(), request.command());
    }

    @PostMapping("/{id}/evaluation-jobs")
    @QuotaProtected(value = UsageFeature.LEARNING_ATTEMPT, idempotentRequest = true)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiJobResponse queueEvaluation(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID id, @Valid @RequestBody LearningEvaluationQueueRequest request) {
        return jobs.evaluateLearning(principal.userId(), id, request.command());
    }

    @PostMapping("/{id}/begin")
    public LearningPlanResponse begin(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        return LearningPlanResponse.from(service.begin(principal.userId(), id));
    }

    @PostMapping("/{id}/attempts")
    @QuotaProtected(UsageFeature.LEARNING_ATTEMPT)
    public LearningPlanResponse attempt(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID id, @Valid @RequestBody LearningAttemptRequest request) {
        return LearningPlanResponse.from(service.submit(principal.userId(), id, request.answers(),
                request.expectedAttemptCount(), request.exercisesVersion(), null));
    }

    @GetMapping("/{id}/attempts")
    public List<LearningAttemptResponse> attempts(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID id) {
        return service.attempts(principal.userId(), id).stream().map(LearningAttemptResponse::from).toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        service.delete(principal.userId(), id);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}/arrangement")
    public LearningPlanResponse updateArrangement(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody LearningArrangementRequest request) {
        return LearningPlanResponse.from(service.updateArrangement(principal.userId(), id, request.reason(), request.scheduledAt()));
    }
}
