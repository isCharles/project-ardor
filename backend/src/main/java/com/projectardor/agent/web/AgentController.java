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
    private final com.projectardor.agent.service.AgentPermissionGrants permissionGrants;

    public AgentController(CareerAgentService agentService, AgentMemoryService memoryService,
            com.projectardor.agent.service.AgentMessageQueue queue,
            com.projectardor.agent.service.AgentPermissionGrants permissionGrants) {
        this.agentService = agentService;
        this.memoryService = memoryService;
        this.queue = queue;
        this.permissionGrants = permissionGrants;
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

    @PostMapping("/messages/steer")
    @QuotaProtected(value = UsageFeature.AGENT_CHAT, idempotentRequest = true)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public com.projectardor.agent.service.AgentMessageQueue.Item steerMessage(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody AgentMessageRequest request) {
        if (request.conversationId() == null) throw new IllegalArgumentException("补充消息需要已有会话");
        agentService.validateQueuedMessage(principal.userId(), request);
        if (request.interruptedRunId() != null) {
            var original = agentService.run(principal.userId(), request.interruptedRunId());
            if (!original.conversationId().equals(request.conversationId())) throw new IllegalArgumentException("不能打断其他会话");
        }
        return queue.enqueueAndReplace(principal.userId(), request, reserved -> {
            if (request.interruptedRunId() != null) {
                var original = agentService.run(principal.userId(), request.interruptedRunId());
                if ("RUNNING".equals(original.status())) agentService.cancelRun(principal.userId(), original.id());
            }
        });
    }
    @DeleteMapping("/queue/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelQueued(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable java.util.UUID id) { queue.cancel(principal.userId(), id); }
    @PostMapping("/queue/{id}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resumeQueued(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable java.util.UUID id) { queue.resume(principal.userId(), id); }

    public record SteerRequest(java.util.UUID interruptRunId) {}
    @PostMapping("/queue/{id}/steer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void steerQueued(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable java.util.UUID id,
            @RequestBody SteerRequest request) {
        queue.steer(principal.userId(), id, queued -> {
        if (request.interruptRunId() != null) {
            var active = agentService.run(principal.userId(), request.interruptRunId());
            if (!active.conversationId().equals(queued.request().conversationId())) throw new IllegalArgumentException("不能打断其他会话");
            if ("RUNNING".equals(active.status())) agentService.cancelRun(principal.userId(), active.id());
        }
        });
    }

    @GetMapping
    public AgentStateResponse state(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam(required = false) java.util.UUID conversationId) {
        return agentService.state(principal.userId(), conversationId);
    }

    @GetMapping("/features")
    public java.util.List<FeatureView> features() {
        return java.util.Arrays.stream(com.projectardor.agent.AgentFeature.values())
                .map(feature -> new FeatureView(feature.name(), feature.id(), feature.label(), feature.englishLabel(), feature.description(), feature.englishDescription())).toList();
    }

    public record FeatureView(String key, java.util.UUID id, String label, String englishLabel, String description, String englishDescription) {}

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

    @GetMapping("/permissions")
    public AgentPermissionsResponse permissions(@AuthenticationPrincipal ArdorPrincipal principal) {
        var permissions = permissionGrants.permissions(principal.userId());
        var modules = java.util.Arrays.stream(com.projectardor.agent.capability.ToolModule.values())
                .map(module -> new AgentModuleGrant(module.key(), permissions.modules().getOrDefault(module, 0),
                        permissionGrants.moduleOverride(principal.userId(), module).isPresent()))
                .toList();
        return new AgentPermissionsResponse(permissions.globalLevel(), modules);
    }

    @PutMapping("/permissions/{module}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updatePermission(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable String module,
            @Valid @RequestBody AgentPermissionUpdateRequest request) {
        if ("*".equals(module)) {
            permissionGrants.setGlobalLevel(principal.userId(), request.level());
            return;
        }
        permissionGrants.setModuleLevel(principal.userId(),
                com.projectardor.agent.capability.ToolModule.fromKey(module), request.level());
    }

    /** Restores the global default for one module, or resets the global level for {@code *}. */
    @DeleteMapping("/permissions/{module}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPermission(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable String module) {
        if ("*".equals(module)) {
            permissionGrants.setGlobalLevel(principal.userId(), 0);
            return;
        }
        permissionGrants.clearModuleLevel(principal.userId(),
                com.projectardor.agent.capability.ToolModule.fromKey(module));
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

    @PatchMapping("/memory/items/{memoryId}")
    public AgentMemoryItemResponse editMemoryItem(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable java.util.UUID memoryId, @Valid @RequestBody AgentMemoryItemUpdateRequest request) {
        return memoryService.edit(principal.userId(), memoryId, request.content());
    }

    @GetMapping("/memory/items")
    public java.util.List<AgentMemoryItemResponse> memoryItems(@AuthenticationPrincipal ArdorPrincipal principal) {
        return memoryService.listItems(principal.userId());
    }

    @PostMapping("/memory/items")
    public AgentMemoryItemResponse addMemoryItem(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody AgentMemoryItemUpdateRequest request) {
        return memoryService.add(principal.userId(), request.content());
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

    @DeleteMapping("/memory/snapshot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMemorySnapshot(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody DeletionSnapshotRequest request) {
        memoryService.removeSnapshot(principal.userId(), request.ids());
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
