package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;

class AgentToolRecoveryTests {

    @Test
    void aFailedToolReturnsSafeGuidanceToTheModelInsteadOfAbortingTheRun() {
        ChatModel model = mock(ChatModel.class);
        var requests = new java.util.ArrayList<ChatRequest>();
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            requests.add(request);
            if (requests.size() == 1) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1").name("explode_tool").arguments("{}").build())))
                        .tokenUsage(new TokenUsage(10, 1)).build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("recovered"))
                    .tokenUsage(new TokenUsage(20, 2)).build();
        });
        Assistant assistant = AiServices.builder(Assistant.class).chatModel(model)
                .tools(new ExplodingTool())
                .toolExecutionErrorHandler(new AgentToolFailureHandler())
                .toolArgumentsErrorHandler(new AgentToolArgumentsErrorHandler())
                .maxToolCallingRoundTrips(3).build();

        assertThat(assistant.chat("synthetic")).isEqualTo("recovered");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).messages()).anySatisfy(message -> {
            assertThat(message).isInstanceOf(ToolExecutionResultMessage.class);
            ToolExecutionResultMessage result = (ToolExecutionResultMessage) message;
            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("TOOL_FAILED", "retryable", "不要重复调用同一写入");
            assertThat(result.text()).doesNotContain("raw-secret-detail");
        });
    }

    @Test
    void failureHandlerDoesNotEchoProviderMessages() {
        var handler = new AgentToolFailureHandler();
        var writeContext = mock(dev.langchain4j.service.tool.ToolErrorContext.class);
        var writeRequest = ToolExecutionRequest.builder().name("fake_write").arguments("{\"value\":\"x\"}").build();
        when(writeContext.toolExecutionRequest()).thenReturn(writeRequest);
        assertThat(handler.handle(new IllegalStateException("raw-secret-detail"), writeContext).text())
                .contains("TOOL_FAILED", "retryable", "不要重复调用同一写入")
                .doesNotContain("raw-secret-detail");

        var readContext = mock(dev.langchain4j.service.tool.ToolErrorContext.class);
        when(readContext.toolExecutionRequest()).thenReturn(
                ToolExecutionRequest.builder().name("list_resumes").arguments("{}").build());
        assertThat(handler.handle(new IllegalStateException("raw-secret-detail"), readContext).text())
                .contains("TOOL_READ_FAILED", "retryable")
                .doesNotContain("raw-secret-detail");
    }

    private interface Assistant {
        String chat(String message);
    }

    public static final class ExplodingTool {
        @Tool(name = "explode_tool", value = "Synthetic failure")
        public String explode() {
            throw new IllegalStateException("raw-secret-detail");
        }
    }
}
