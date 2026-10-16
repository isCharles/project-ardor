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

/** Execution fence for all tools. Destructive adapters only prepare UI proposals. */
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
                boolean destructive = specification.name().startsWith("delete_") || specification.name().equals("clear_all_memories");
                boolean proposal = PROPOSALS.contains(specification.name());
                boolean read = ToolIntentPolicy.isRead(specification.name());
                boolean allowed = (!destructive || proposal) && (proposal || read || requestId != null)
                        && approvals.canPropose(userId, requestId);
                long requested = timings.now();
                if (allowed && !proposal && !read) {
                    allowed = approvals.await(userId, requestId, specification.name(), request.arguments())
                            && approvals.canPropose(userId, requestId);
                }
                long approved = timings.now();
                if (!allowed) {
                    timings.record(request.id(), requested, approved, false);
                    return "{\"status\":\"PERMISSION_NOT_GRANTED\",\"message\":\"用户未确认、已拒绝、确认已过期或执行已停止。未执行本次操作，不要重复请求或声称已完成。\"}";
                }
                java.util.concurrent.atomic.AtomicBoolean executed = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.function.Supplier<String> call = () -> { executed.set(true); return delegate.execute(request, memoryId); };
                try { return lease == null ? call.get() : lease.execute(call); }
                finally { timings.record(request.id(), requested, approved, executed.get()); }
            });
        }
        return result;
    }
}
