package com.projectardor.assistant.execution.service;

import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;

/**
 * Converts a tool exception into bounded, safe guidance for the model.
 *
 * <p>The tool audit row and the trace span already carry the failure. The raw exception is not
 * echoed to the model because provider/service messages may contain user content or internal
 * identifiers. Reads may be retried; writes must be verified first because a thrown write could
 * have produced a partial side effect. A rejected argument or a missing target is safe to correct
 * and retry because the business service rejected it before mutating anything.</p>
 */
public final class AgentToolFailureHandler implements ToolExecutionErrorHandler {

    private static final String READ_FAILURE = """
            {"status":"TOOL_READ_FAILED","retryable":true,"message":"读取工具执行失败，没有确认到写入结果。可以重新读取当前状态后再决定；不要声称已经读取成功。"}
            """.strip();
    private static final String WRITE_FAILURE = """
            {"status":"TOOL_FAILED","retryable":false,"message":"写入工具执行失败，无法确认是否已经产生副作用。不要重复调用同一写入；先读取当前状态并向用户说明需要核验结果。"}
            """.strip();
    private static final String CORRECTABLE_FAILURE = """
            {"status":"TOOL_ARGUMENTS_REJECTED","retryable":true,"message":"业务校验没有通过，本次操作未成功。请修正参数或改用读取到的真实目标；完全相同的调用不要重复。"}
            """.strip();

    @Override
    public ToolErrorHandlerResult handle(Throwable error, ToolErrorContext context) {
        String tool = context == null || context.toolExecutionRequest() == null
                ? "" : context.toolExecutionRequest().name();
        if (correctable(error)) return ToolErrorHandlerResult.text(CORRECTABLE_FAILURE);
        return ToolErrorHandlerResult.text(ToolIntentPolicy.isRead(tool) ? READ_FAILURE : WRITE_FAILURE);
    }

    private static boolean correctable(Throwable error) {
        Throwable cause = error instanceof ToolExecutionException && error.getCause() != null
                ? error.getCause() : error;
        return cause instanceof IllegalArgumentException
                || cause instanceof com.projectardor.common.web.ResourceNotFoundException;
    }
}
