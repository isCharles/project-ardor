package com.projectardor.jobsearch.discovery.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.projectardor.jobsearch.discovery.service.JobDiscoveryService;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/job-discovery")
public class JobDiscoveryController {

    private final JobDiscoveryService service;

    public JobDiscoveryController(JobDiscoveryService service) {
        this.service = service;
    }

    public record CriteriaRequest(List<String> roles, List<String> locations, List<String> keywords) {}

    public record StartRunRequest(@NotNull UUID requestId, List<String> roles, List<String> locations,
            List<String> keywords) {}

    public record PostingStateRequest(@NotBlank String state) {}

    public record AppliedRequest(boolean confirm) {}

    @GetMapping("/preferences")
    public JobDiscoveryService.PreferencesView preferences(@AuthenticationPrincipal ArdorPrincipal principal) {
        return service.preferences(principal.userId());
    }

    @PutMapping("/preferences")
    public JobDiscoveryService.PreferencesView savePreferences(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestBody CriteriaRequest request) {
        return service.savePreferences(principal.userId(), criteria(request));
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDiscoveryService.RunView start(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody StartRunRequest request) {
        return service.start(principal.userId(), request.requestId(), criteria(request));
    }

    @GetMapping("/runs/{runId}")
    public JobDiscoveryService.RunView run(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID runId) {
        return service.run(principal.userId(), runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public JobDiscoveryService.RunView cancel(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID runId) {
        return service.cancel(principal.userId(), runId);
    }

    @PostMapping("/runs/{runId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDiscoveryService.RunView retry(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID runId) {
        return service.retry(principal.userId(), runId);
    }

    @GetMapping("/postings")
    public List<JobDiscoveryService.PostingView> postings(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam(required = false) String state,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return service.list(principal.userId(), state, query, limit, offset);
    }

    @PatchMapping("/postings/{postingId}")
    public JobDiscoveryService.PostingView updateState(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID postingId, @Valid @RequestBody PostingStateRequest request) {
        return service.updateState(principal.userId(), postingId, request.state());
    }

    @PostMapping("/postings/{postingId}/applied")
    public JobDiscoveryService.PostingView markApplied(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID postingId, @RequestBody AppliedRequest request) {
        return service.markApplied(principal.userId(), postingId, request.confirm());
    }

    @GetMapping("/summary")
    public JobDiscoveryService.SummaryView summary(@AuthenticationPrincipal ArdorPrincipal principal) {
        return service.summary(principal.userId());
    }

    private static JobDiscoveryService.Criteria criteria(CriteriaRequest request) {
        return new JobDiscoveryService.Criteria(request.roles(), request.locations(), request.keywords());
    }

    private static JobDiscoveryService.Criteria criteria(StartRunRequest request) {
        return new JobDiscoveryService.Criteria(request.roles(), request.locations(), request.keywords());
    }
}
