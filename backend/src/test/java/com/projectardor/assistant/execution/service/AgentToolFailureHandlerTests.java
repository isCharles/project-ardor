package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.projectardor.common.web.ResourceNotFoundException;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolErrorContext;

class AgentToolFailureHandlerTests {

    private final AgentToolFailureHandler handler = new AgentToolFailureHandler();

    @Test
    void rejectedArgumentsInviteACorrectedRetry() {
        var result = handler.handle(new ToolExecutionException(
                new IllegalArgumentException("时间必须使用带时区的 ISO-8601 格式")), context("create_calendar_task"));

        assertThat(result.text()).contains("TOOL_ARGUMENTS_REJECTED", "\"retryable\":true");
        assertThat(result.text()).doesNotContain("无法确认是否已经产生副作用");
    }

    @Test
    void missingTargetIsCorrectableAfterAFreshRead() {
        var result = handler.handle(new ToolExecutionException(
                new ResourceNotFoundException("日程不存在")), context("update_calendar_task"));

        assertThat(result.text()).contains("TOOL_ARGUMENTS_REJECTED", "\"retryable\":true");
    }

    @Test
    void unknownWriteFailureStillRequiresVerification() {
        var result = handler.handle(new ToolExecutionException(new IllegalStateException("database down")),
                context("create_calendar_task"));

        assertThat(result.text()).contains("TOOL_FAILED", "\"retryable\":false");
        assertThat(result.text()).doesNotContain("完全相同的调用不要重复");
    }

    @Test
    void readFailureStaysRetryableWithoutClaimingSuccess() {
        var result = handler.handle(new RuntimeException("synthetic read failure"), context("list_calendar_tasks"));

        assertThat(result.text()).contains("TOOL_READ_FAILED", "\"retryable\":true");
    }

    private static ToolErrorContext context(String tool) {
        return ToolErrorContext.builder()
                .toolExecutionRequest(ToolExecutionRequest.builder().name(tool).arguments("{}").build())
                .invocationContext(InvocationContext.builder().build())
                .build();
    }
}
