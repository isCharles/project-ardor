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
    private final com.projectardor.agent.service.AgentMessageQueue queue;

    public AgentController(CareerAgentService agentService, AgentMemoryService memoryService,
            com.projectardor.agent.service.AgentMessageQueue queue) {
        this.agentService = agentService;
        this.memoryService = memoryService;
        this.queue = queue;
    }

    @PostMapping("/queue")
    @QuotaProtected(value = UsageFeature.AGENT_CHAT, idempotentRequest = true)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public com.projectardor.agent.service.AgentMessageQueue.Item enqueue(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody AgentMessageRequest request) {
        if (request.conversationId() == null) throw new IllegalArgumentException("排队消息需要已有会话");
        agentService.validateQueuedMessage(principal.userId(), request);
        return queue.enqueue(principal.userId(), request);
    }
    @GetMapping("/queue")
    public java.util.List<com.projectardor.agent.service.AgentMessageQueue.Item> queued(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam java.util.UUID conversationId) { return queue.list(principal.userId(), conversationId); }
    @DeleteMapping("/queue/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelQueued(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable java.util.UUID id) { queue.cancel(principal.userId(), id); }
    @PostMapping("/queue/{id}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resumeQueued(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable java.util.UUID id) { queue.resume(principal.userId(), id); }

    @GetMapping
    public AgentStateResponse state(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam(required = false) java.util.UUID conversationId) {
        return agentService.state(principal.userId(), conversationId);
    }

    @GetMapping("/features")
    public java.util.List<FeatureView> features() {
        return java.util.Arrays.stream(com.projectardor.agent.AgentFeature.values())
                .map(feature -> new FeatureView(feature.name(), feature.id(), feature.label(), feature.englishLabel(), feature.description())).toList();
    }

    public record FeatureView(String key, java.util.UUID id, String label, String englishLabel, String description) {}

    @PostMapping("/messages")
    @QuotaProtected(UsageFeature.AGENT_CHAT)
    public AgentMessageResponse chat(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody AgentMessageRequest request) {
        return agentService.chat(principal.userId(), request.conversationId(), request.message(), request.contextType(),
                request.contextId(), request.contextReferences(), request.requestId(), request.reasoningDepth());
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @QuotaProtected(value = UsageFeature.AGENT_CHAT, idempotentRequest = true)
    public SseEmitter chatStream(
            @AuthenticationPrincipal ArdorPrincipal principal,
            HttpServletResponse response,
            @Valid @RequestBody AgentMessageRequest request) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        // The runtime enforces silence and total deadlines. Transport lifetime must not cut off an active run at five minutes.
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> { closed.set(true); emitter.complete(); });
        agentService.chatStream(principal.userId(), request.conversationId(), request.message(), request.contextType(), request.contextId(),
                request.contextReferences(), request.requestId(), request.reasoningDepth(),
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

    @GetMapping("/reasoning")
    public java.util.Map<String, Boolean> reasoning(@AuthenticationPrincipal ArdorPrincipal principal) {
        return java.util.Map.of("nativeControl", agentService.nativeReasoningControl(principal.userId()));
    }

    @PostMapping("/runs/{requestId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelRun(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable java.util.UUID requestId) {
        agentService.cancelRun(principal.userId(), requestId);
    }

    @GetMapping("/runs/{requestId}/approvals")
    public java.util.List<com.projectardor.agent.service.AgentToolApprovals.View> approvals(
            @AuthenticationPrincipal ArdorPrincipal principal, @PathVariable java.util.UUID requestId) {
        return agentService.approvals(principal.userId(), requestId);
    }

    @PostMapping("/runs/{requestId}/approvals/{approvalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decideApproval(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable java.util.UUID requestId, @PathVariable java.util.UUID approvalId,
            @Valid @RequestBody ApprovalDecision decision) {
        agentService.decideApproval(principal.userId(), requestId, approvalId, decision.allow());
    }
    public record ApprovalDecision(@jakarta.validation.constraints.NotNull Boolean allow) {}

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

    @GetMapping("/runs/{requestId}/progress")
    public com.projectardor.agent.service.AgentStreamProgress.View progress(
            @AuthenticationPrincipal ArdorPrincipal principal, @PathVariable java.util.UUID requestId) {
        return agentService.progress(principal.userId(), requestId);
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentConversationResponse createConversation(
            @AuthenticationPrincipal ArdorPrincipal principal) {
        return agentService.createConversation(principal.userId());
    }

    @GetMapping("/conversations")
    public java.util.List<AgentConversationResponse> conversations(@AuthenticationPrincipal ArdorPrincipal principal) {
        return agentService.conversations(principal.userId());
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
