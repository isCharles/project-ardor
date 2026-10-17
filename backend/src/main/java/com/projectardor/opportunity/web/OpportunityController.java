package com.projectardor.opportunity.web;

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

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.opportunity.service.OpportunityService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/opportunities")
public class OpportunityController {
    private final OpportunityService service;

    public OpportunityController(OpportunityService service) {
        this.service = service;
    }

    @GetMapping
    public List<OpportunityResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return service.list(principal.userId());
    }

    @GetMapping("/{opportunityId}")
    public OpportunityResponse detail(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID opportunityId) {
        return service.detail(principal.userId(), opportunityId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OpportunityResponse create(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody OpportunityCreateRequest request) {
        return service.create(principal.userId(), request);
    }

    @PatchMapping("/{opportunityId}")
    public OpportunityResponse update(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID opportunityId,
            @Valid @RequestBody OpportunityUpdateRequest request) {
        return service.update(principal.userId(), opportunityId, request);
    }

    @PostMapping("/{opportunityId}/rounds")
    @ResponseStatus(HttpStatus.CREATED)
    public OpportunityResponse addRound(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID opportunityId,
            @Valid @RequestBody OpportunityRoundRequest request) {
        return service.addRound(principal.userId(), opportunityId, request);
    }

    @PatchMapping("/{opportunityId}/rounds/{roundId}")
    public OpportunityResponse updateRound(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID opportunityId,
            @PathVariable UUID roundId,
            @Valid @RequestBody OpportunityRoundRequest request) {
        return service.updateRound(principal.userId(), opportunityId, roundId, request);
    }

    @DeleteMapping("/{opportunityId}/rounds/{roundId}")
    public OpportunityResponse deleteRound(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID opportunityId,
            @PathVariable UUID roundId) {
        return service.deleteRound(principal.userId(), opportunityId, roundId);
    }

    @DeleteMapping("/{opportunityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID opportunityId) {
        service.delete(principal.userId(), opportunityId);
    }
}
