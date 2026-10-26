package com.projectardor.assistant.execution.web;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.assistant.execution.service.AgentRunStore;
import com.projectardor.assistant.execution.service.AgentStreamProgress;
import com.projectardor.assistant.execution.service.AgentTraceStore;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

/** Multi-turn, owner-scoped trace for a whole conversation. */
@RestController
@RequestMapping("/api/agent/conversations")
public class AgentConversationTraceController {
    private final AgentTraceStore traceStore;
    private final AgentRunStore runStore;
    private final AgentConversationStore conversations;
    private final AgentStreamProgress progress;

    public AgentConversationTraceController(AgentTraceStore traceStore, AgentRunStore runStore,
            AgentConversationStore conversations, AgentStreamProgress progress) {
        this.traceStore = traceStore;
        this.runStore = runStore;
        this.conversations = conversations;
        this.progress = progress;
    }

    public AgentConversationTraceResponse trace(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID conversationId) {
        return tracePage(principal, conversationId, null, 30);
    }

    @GetMapping("/{conversationId}/trace")
    public AgentConversationTraceResponse tracePage(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID conversationId, @RequestParam(required = false) UUID before,
            @RequestParam(defaultValue = "30") int limit) {
        var conversation = conversations.requireActive(principal.userId(), conversationId);
        var page = runStore.tracePage(principal.userId(), conversationId, before, limit);
        var assistantByRun = new HashMap<UUID, String>();
        for (var message : conversations.assistantMessagesForRuns(principal.userId(), conversationId,
                page.runs().stream().map(AgentRunStore.AgentRun::id).toList())) {
            if ("ASSISTANT".equals(message.getRole()) && message.getRequestId() != null) {
                assistantByRun.put(message.getRequestId(), message.getContent());
            }
        }
        List<AgentConversationTraceResponse.RunTrace> runs = page.runs().stream()
                .map(run -> new AgentConversationTraceResponse.RunTrace(
                        run.id(), run.message(), assistantByRun.getOrDefault(run.id(), ""),
                        thinking(principal.userId(), run.id()), run.status(),
                        run.createdAt(), run.updatedAt(),
                        traceStore.find(principal.userId(), run.id()).stream()
                                .map(AgentTraceSpanResponse::from).toList()))
                .toList();
        return new AgentConversationTraceResponse(conversation.getId(), conversation.getTitle(), runs, page.nextBefore());
    }

    private String thinking(UUID userId, UUID runId) {
        try {
            return progress.get(userId, runId).thinkingOutput();
        } catch (RuntimeException missing) {
            return "";
        }
    }
}

