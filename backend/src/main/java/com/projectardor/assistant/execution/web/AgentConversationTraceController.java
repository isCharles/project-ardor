package com.projectardor.assistant.execution.web;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.assistant.execution.service.AgentRunStore;
import com.projectardor.assistant.execution.service.AgentTraceStore;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

/** Multi-turn, owner-scoped trace for a whole conversation. */
@RestController
@RequestMapping("/api/agent/conversations")
public class AgentConversationTraceController {
    private final AgentTraceStore traceStore;
    private final AgentRunStore runStore;
    private final AgentConversationStore conversations;

    public AgentConversationTraceController(AgentTraceStore traceStore, AgentRunStore runStore,
            AgentConversationStore conversations) {
        this.traceStore = traceStore;
        this.runStore = runStore;
        this.conversations = conversations;
    }

    @GetMapping("/{conversationId}/trace")
    public AgentConversationTraceResponse trace(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID conversationId) {
        var conversation = conversations.requireActive(principal.userId(), conversationId);
        var assistantByRun = new HashMap<UUID, String>();
        for (var message : conversations.allMessages(principal.userId(), conversationId)) {
            if ("ASSISTANT".equals(message.getRole()) && message.getRequestId() != null) {
                assistantByRun.put(message.getRequestId(), message.getContent());
            }
        }
        List<AgentConversationTraceResponse.RunTrace> runs = runStore
                .recentForConversation(principal.userId(), conversationId, 30).stream()
                .map(run -> new AgentConversationTraceResponse.RunTrace(
                        run.id(), run.message(), assistantByRun.getOrDefault(run.id(), ""), run.status(),
                        run.createdAt(), run.updatedAt(),
                        traceStore.find(principal.userId(), run.id()).stream()
                                .map(AgentTraceSpanResponse::from).toList()))
                .toList();
        return new AgentConversationTraceResponse(conversation.getId(), conversation.getTitle(), runs);
    }
}
