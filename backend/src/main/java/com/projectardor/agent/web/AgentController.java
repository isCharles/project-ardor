package com.projectardor.agent.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectardor.agent.service.CareerAgentService;
import com.projectardor.agent.service.AgentMemoryService;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.usage.QuotaProtected;
import com.projectardor.usage.UsageFeature;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final CareerAgentService agentService;
    private final AgentMemoryService memoryService;

    public AgentController(CareerAgentService agentService, AgentMemoryService memoryService) {
        this.agentService = agentService;
        this.memoryService = memoryService;
    }

    @GetMapping
    public AgentStateResponse state(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam(required = false) java.util.UUID conversationId) {
        return agentService.state(principal.userId(), conversationId);
    }

    @PostMapping("/messages")
    @QuotaProtected(UsageFeature.AGENT_CHAT)
    public AgentMessageResponse chat(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody AgentMessageRequest request) {
        return agentService.chat(principal.userId(), request.conversationId(), request.message(), request.contextType(),
                request.contextId(), request.contextReferences());
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @QuotaProtected(value = UsageFeature.AGENT_CHAT, idempotentRequest = true)
    public SseEmitter chatStream(
            @AuthenticationPrincipal ArdorPrincipal principal,
            HttpServletResponse response,
            @Valid @RequestBody AgentMessageRequest request) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> { closed.set(true); emitter.complete(); });
        agentService.chatStream(principal.userId(), request.conversationId(), request.message(), request.contextType(), request.contextId(),
                request.contextReferences(), request.requestId(),
                new CareerAgentService.StreamSink() {
                    @Override public void send(AgentStreamEvent event) {
                        if (closed.get()) return;
                        try { emitter.send(SseEmitter.event().name(event.type()).data(event)); }
                        catch (IOException | IllegalStateException exception) { closed.set(true); }
                    }
                    @Override public void complete() {
                        if (closed.compareAndSet(false, true)) emitter.complete();
                    }
                });
        return emitter;
    }

    @GetMapping("/runs/{requestId}")
    public AgentRunResponse run(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable java.util.UUID requestId) {
        return agentService.run(principal.userId(), requestId);
    }

    @GetMapping("/runs/latest")
    public AgentRunResponse latestRun(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam java.util.UUID conversationId) {
        return agentService.latestRun(principal.userId(), conversationId);
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentConversationResponse createConversation(
            @AuthenticationPrincipal ArdorPrincipal principal) {
        return agentService.createConversation(principal.userId());
    }

    @PatchMapping("/conversations/{conversationId}")
    public AgentConversationResponse renameConversation(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable java.util.UUID conversationId,
            @Valid @RequestBody AgentConversationRenameRequest request) {
        return agentService.renameConversation(principal.userId(), conversationId, request.title());
    }

    @PatchMapping("/conversations/{conversationId}/pin")
    public AgentConversationResponse pinConversation(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable java.util.UUID conversationId,
            @RequestBody AgentConversationPinRequest request) {
        return agentService.pinConversation(principal.userId(), conversationId, request.pinned());
    }

    @PostMapping("/conversations/{conversationId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveConversation(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable java.util.UUID conversationId) {
        agentService.archiveConversation(principal.userId(), conversationId);
    }

    @PostMapping("/conversations/{conversationId}/restore")
    public AgentConversationResponse restoreConversation(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable java.util.UUID conversationId) {
        return agentService.restoreConversation(principal.userId(), conversationId);
    }

    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable java.util.UUID conversationId) {
        agentService.deleteConversation(principal.userId(), conversationId);
    }

    @GetMapping("/memory")
    public AgentMemoryResponse memory(@AuthenticationPrincipal ArdorPrincipal principal) {
        return memoryService.get(principal.userId());
    }

    @PutMapping("/memory")
    public AgentMemoryResponse updateMemory(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody AgentMemoryUpdateRequest request) {
        return memoryService.update(principal.userId(), request.content());
    }

    @DeleteMapping("/memory")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMemory(@AuthenticationPrincipal ArdorPrincipal principal) {
        memoryService.clear(principal.userId());
    }

    /** One memory, removed by the user — this is where a confirmation button lands. */
    @DeleteMapping("/memory/items/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMemoryItem(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable java.util.UUID memoryId) {
        memoryService.remove(principal.userId(), memoryId);
    }
}
