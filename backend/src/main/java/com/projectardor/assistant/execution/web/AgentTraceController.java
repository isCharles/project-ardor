package com.projectardor.assistant.execution.web;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.assistant.execution.service.AgentRunStore;
import com.projectardor.assistant.execution.service.AgentTraceStore;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

@RestController
@RequestMapping("/api/agent/runs")
public class AgentTraceController {

    private final AgentTraceStore traceStore;
    private final AgentRunStore runStore;

    public AgentTraceController(AgentTraceStore traceStore, AgentRunStore runStore) {
        this.traceStore = traceStore;
        this.runStore = runStore;
    }

    @GetMapping("/{requestId}/trace")
    public AgentTraceResponse trace(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID requestId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "128") int limit) {
        // Owner-scoped lookup first: unknown and foreign runs get the same 404, so nothing is revealed,
        // and a run that exists but has no spans is a different fact and must not be reported as one.
        var run = runStore.get(principal.userId(), requestId);
        var root = traceStore.findRoot(principal.userId(), requestId).orElse(null);
        var page = traceStore.findPage(principal.userId(), requestId, offset, limit);
        boolean recorded = root != null || !page.spans().isEmpty();
        String recordState = recorded ? "RECORDED"
                : "RUNNING".equals(run.status()) ? "PREPARING" : "NOT_RECORDED";
        return AgentTraceResponse.from(requestId, run.conversationId(),
                root == null ? null : AgentTraceSpanResponse.from(root),
                page.spans().stream().map(AgentTraceSpanResponse::from).toList(),
                page.totalSpans(), page.nextOffset(), recordState);
    }
}



