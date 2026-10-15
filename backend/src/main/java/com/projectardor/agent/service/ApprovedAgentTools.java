package com.projectardor.agent.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

/** All executable tools pass this boundary, including legacy synchronous chat. */
final class ApprovedAgentTools {
    // These adapters ONLY construct existing UI confirmation proposals. They never mutate business data.
    private static final Set<String> PROPOSALS = Set.of("create_learning_plan", "delete_learning_plan",
            "delete_knowledge_document", "delete_memory", "clear_all_memories", "delete_calendar_task",
            "delete_recurring_task", "delete_resume", "delete_interview", "delete_memory_card",
            "delete_all_memory_cards", "delete_interview_recap");
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals) {
        return wrap(bound, userId, requestId, approvals, null);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease) {
        return wrap(bound, userId, requestId, approvals, lease, new AgentToolTimings());
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings) {
        Map<ToolSpecification, ToolExecutor> result = new LinkedHashMap<>();
        for (var method : bound.getClass().getMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) continue;
            var specification = ToolSpecifications.toolSpecificationFrom(method);
            var delegate = new DefaultToolExecutor(bound, method);
            result.put(specification, (request, memoryId) -> {
                if (!specification.name().equals(request.name())) throw new IllegalArgumentException("工具名称不匹配");
                long started = timings.now();
                boolean allowed = PROPOSALS.contains(specification.name())
                        ? approvals.canPropose(userId, requestId)
                        : approvals.await(userId, requestId, specification.name(), request.arguments());
                long approved = timings.now();
                if (!allowed) {
                    timings.record(request.id(), started, approved, false);
                    return "{\"status\":\"PERMISSION_NOT_GRANTED\",\"message\":\"用户未批准、已拒绝或权限请求过期。未执行工具，本轮不得重复申请或用聊天口令代替批准。\"}";
                }
                java.util.concurrent.atomic.AtomicBoolean executed = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.function.Supplier<String> call = () -> { executed.set(true); return delegate.execute(request, memoryId); };
                try { return lease == null ? call.get() : lease.execute(call); }
                finally { timings.record(request.id(), started, approved, executed.get()); }
            });
        }
        return result;
    }
}
