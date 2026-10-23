package com.projectardor.assistant.execution.service;

import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;

/**
 * Invalid tool arguments did not execute anything. Give the model a compact correction hint
 * without echoing raw argument text or parser internals.
 */
public final class AgentToolArgumentsErrorHandler implements ToolArgumentsErrorHandler {

    private static final String INVALID_ARGUMENTS = """
            {"status":"TOOL_ARGUMENTS_INVALID","retryable":true,"message":"工具参数无法解析，本次没有执行任何操作。请修正参数格式后重试这一次调用。"}
            """.strip();

    @Override
    public ToolErrorHandlerResult handle(Throwable error, ToolErrorContext context) {
        return ToolErrorHandlerResult.text(INVALID_ARGUMENTS);
    }
}
