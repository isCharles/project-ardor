package com.projectardor.agent.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.agent.service.AgentExecutionAuditStore;
import com.projectardor.agent.service.AgentRunStore;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

/**
 * Read-only execution/audit views for one owned agent run.
 *
 * <p>Ownership is resolved from the authenticated principal and checked through the durable run
 * row first, so another user's run is a 404. The rows carry only digests and bounded references;
 * they never grant permission and never prove a business mutation succeeded.
 */
@RestController
@RequestMapping("/api/agent/runs")
public class AgentExecutionAuditController {

    private final AgentRunStore runs;
    private final AgentExecutionAuditStore executionAudit;

    public AgentExecutionAuditController(AgentRunStore runs, AgentExecutionAuditStore executionAudit) {
        this.runs = runs;
        this.executionAudit = executionAudit;
    }

    @GetMapping("/{requestId}/tool-calls")
    public List<AgentToolCallResponse> toolCalls(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID requestId) {
        AgentRunStore.AgentRun run = ownedRun(principal, requestId);
        return executionAudit.toolCalls(principal.userId(), run.id()).stream()
                .map(AgentToolCallResponse::from).toList();
    }

    @GetMapping("/{requestId}/audit")
    public AgentRunAuditResponse audit(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID requestId) {
        AgentRunStore.AgentRun run = ownedRun(principal, requestId);
        return new AgentRunAuditResponse(run.id(), run.assistantMessageId(),
                executionAudit.toolCalls(principal.userId(), run.id()).stream()
                        .map(AgentToolCallResponse::from).toList(),
                executionAudit.proposals(principal.userId(), run.id()).stream()
                        .map(AgentProposalAuditResponse::from).toList(),
                executionAudit.auditEvents(principal.userId(), run.id()).stream()
                        .map(AgentAuditEventResponse::from).toList());
    }

    private AgentRunStore.AgentRun ownedRun(ArdorPrincipal principal, UUID requestId) {
        // Throws 404 for an unknown or foreign run without revealing which case it was.
        return runs.get(principal.userId(), requestId);
    }
}
