package com.projectardor.assistant.execution.web;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.assistant.execution.service.AgentTraceStore;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

@RestController
@RequestMapping("/api/agent/runs")
public class AgentTraceController {

    private final AgentTraceStore traceStore;

    public AgentTraceController(AgentTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @GetMapping("/{requestId}/trace")
    public AgentTraceResponse trace(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID requestId) {
        var spans = traceStore.find(principal.userId(), requestId);
        if (spans.isEmpty()) {
            // Same response for unknown and foreign runs: never reveal ownership.
            throw new ResourceNotFoundException("执行记录不存在");
        }
        return AgentTraceResponse.from(requestId,
                spans.stream().map(AgentTraceSpanResponse::from).toList());
    }
}
