package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

class AgentRequestContextListenerTests {
    @Test void includesSchemasAndToolResultsAndUsesLatestRequestRatherThanCumulativeUsage() {
        var values = new ArrayList<AgentContextUsage>();
        var active = new AtomicBoolean(true);
        var base = new AgentContextUsage("READY", "test", null, 12000, 100000L, 2, 8);
        var listener = new AgentRequestContextListener(null, base, values::add, active::get);
        var first = request(ChatRequest.builder().messages(UserMessage.from("question")).build());
        listener.onRequest(first);
        long plain = values.getLast().estimatedTokens();
        var schemaOnly = request(ChatRequest.builder().messages(UserMessage.from("question"))
                .toolSpecifications(ToolSpecification.builder().name("read").description("Reads owned resources").build()).build());
        listener.onRequest(schemaOnly);
        long withSchema = values.getLast().estimatedTokens();
        assertThat(withSchema).isGreaterThan(plain);
        var second = request(ChatRequest.builder().messages(UserMessage.from("question"),
                ToolExecutionResultMessage.from("id", "read", "result ".repeat(200)))
                .toolSpecifications(ToolSpecification.builder().name("read").description("Reads owned resources").build()).build());
        listener.onRequest(second);
        assertThat(values.getLast().estimatedTokens()).isGreaterThan(withSchema);
        int count = values.size();
        listener.onResponse(response(first, 99999));
        assertThat(values).hasSize(count); // Late result from the previous call is fenced.
        listener.onResponse(response(second, 501));
        assertThat(values.getLast().estimatedTokens()).isEqualTo(501);
        var third = request(ChatRequest.builder().messages(UserMessage.from("short summary")).build());
        listener.onRequest(third);
        assertThat(values.getLast().estimatedTokens()).isLessThan(501);
        assertThat(values.getLast().summaryVersion()).isEqualTo(2);
        count = values.size();
        active.set(false);
        listener.onResponse(response(third, 800));
        listener.onRequest(first);
        assertThat(values).hasSize(count);
    }

    private static ChatModelRequestContext request(ChatRequest request) {
        var context = mock(ChatModelRequestContext.class);
        when(context.chatRequest()).thenReturn(request);
        when(context.attributes()).thenReturn(new HashMap<>());
        return context;
    }
    private static ChatModelResponseContext response(ChatModelRequestContext request, int tokens) {
        var context = mock(ChatModelResponseContext.class);
        var attributes = request.attributes();
        when(context.attributes()).thenReturn(attributes);
        when(context.chatResponse()).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("answer"))
                .tokenUsage(new TokenUsage(tokens, 5)).build());
        return context;
    }
}
