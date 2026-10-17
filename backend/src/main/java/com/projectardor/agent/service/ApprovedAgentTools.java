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
            "delete_all_memory_cards", "delete_interview_recap", "delete_job_opportunity",
            "delete_job_opportunity_round");
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals) {
        return wrap(bound, userId, requestId, approvals, null, new AgentToolTimings(), waiting -> {}, null);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, String userRequest) {
        return wrap(bound, userId, requestId, approvals, null, new AgentToolTimings(), waiting -> {}, userRequest);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease) {
        return wrap(bound, userId, requestId, approvals, lease, new AgentToolTimings(), waiting -> {}, null);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings) {
        return wrap(bound, userId, requestId, approvals, lease, timings, waiting -> {}, null);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings, java.util.function.Consumer<Boolean> waiting) {
        return wrap(bound, userId, requestId, approvals, lease, timings, waiting, null);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings, java.util.function.Consumer<Boolean> waiting, String userRequest) {
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
                        && (read ? approvals.canRead(userId, requestId) : approvals.canPropose(userId, requestId));
                long requested = timings.now();
                if (allowed && !proposal && !read) {
                    // Memory writes are validated before asking: a rewritten memory can
                    // never pass the verbatim rule, so approving it would only waste a dialog.
                    String rejection = memoryWriteRejection(specification.name(), request.arguments(), userRequest);
                    if (rejection != null) {
                        timings.record(request.id(), requested, timings.now(), false);
                        return "{\"status\":\"INVALID_ARGUMENT\",\"message\":\"" + rejection + "\"}";
                    }
                    waiting.accept(true);
                    try { allowed = approvals.await(userId, requestId, specification.name(), request.arguments())
                            && approvals.canPropose(userId, requestId); }
                    finally { waiting.accept(false); }
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

    /**
     * Guards the two memory writes whose tool bodies enforce a verbatim user-quote rule.
     * Runs before approval so an unapprovable call never opens a confirmation dialog.
     */
    static String memoryWriteRejection(String tool, String arguments, String userRequest) {
        if (userRequest == null) return null;
        String value = switch (tool) {
            case "add_memory" -> argumentString(arguments, "memory");
            case "update_memory" -> argumentString(arguments, "content");
            default -> null;
        };
        if (value == null) return null;
        if (value.isBlank()) {
            return tool.equals("update_memory") ? "修订后的记忆不能为空。" : "长期记忆不能为空。";
        }
        if (ToolIntentPolicy.groundedMemory(userRequest, value)) return null;
        return "这条记忆必须逐字引用用户本轮说过的原话，不能改写、补充或合并；请直接把原话作为记忆重试一次。"
                + "如果原话不足以成为一条完整记忆，就告诉用户这样保存不了，不要继续换说法尝试。";
    }

    /** Reads one top-level string field from a tool-argument JSON without pulling in a parser. */
    static String argumentString(String arguments, String key) {
        if (arguments == null) return null;
        String needle = "\"" + key + "\"";
        int at = arguments.indexOf(needle);
        if (at < 0) return null;
        int index = at + needle.length();
        while (index < arguments.length() && Character.isWhitespace(arguments.charAt(index))) index++;
        if (index >= arguments.length() || arguments.charAt(index) != ':') return null;
        index++;
        while (index < arguments.length() && Character.isWhitespace(arguments.charAt(index))) index++;
        if (index >= arguments.length() || arguments.charAt(index) != '"') return null;
        index++;
        StringBuilder value = new StringBuilder();
        while (index < arguments.length()) {
            char current = arguments.charAt(index++);
            if (current == '"') return value.toString();
            if (current != '\\' || index >= arguments.length()) {
                value.append(current);
                continue;
            }
            char escaped = arguments.charAt(index++);
            switch (escaped) {
                case 'n' -> value.append('\n');
                case 't' -> value.append('\t');
                case 'r' -> value.append('\r');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'u' -> {
                    if (index + 4 > arguments.length()) return null;
                    try { value.append((char) Integer.parseInt(arguments.substring(index, index + 4), 16)); }
                    catch (NumberFormatException exception) { return null; }
                    index += 4;
                }
                default -> value.append(escaped);
            }
        }
        return null;
    }
}
