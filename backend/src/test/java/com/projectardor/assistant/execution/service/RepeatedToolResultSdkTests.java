package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import com.sun.net.httpserver.HttpServer;
import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

class RepeatedToolResultSdkTests {
    @Test void actualSdkPayloadAndTelemetryBothReceiveTheSameReducedHistory() throws Exception {
        for (var provider : LlmProvider.values()) {
            var captured = new AtomicReference<String>();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                String events = provider == LlmProvider.OPENAI_COMPATIBLE ? """
                        data: {"id":"fixture","object":"chat.completion.chunk","model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"}}]}

                        data: {"id":"fixture","object":"chat.completion.chunk","model":"fixture-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """ : """
                        event: message_start
                        data: {"type":"message_start","message":{"id":"fixture","type":"message","role":"assistant","model":"fixture-model","content":[],"usage":{"input_tokens":10,"output_tokens":0}}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

                        event: content_block_stop
                        data: {"type":"content_block_stop","index":0}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """;
                exchange.getResponseBody().write(events.getBytes(StandardCharsets.UTF_8));
                exchange.close();
            });
            server.start();
            try {
                var budget = new AgentContextBudgetPolicy(new ModelContextCapacity(new StandardEnvironment()), 1000, .75, .05);
                var factory = new LangChainModelFactory(mock(LlmConfigService.class), "", 0, Duration.ofSeconds(2), Duration.ofSeconds(5),
                        List.of(new OpenAiAgentModelProtocol(), new AnthropicAgentModelProtocol()), budget);
                var config = new LlmConfigService.LlmRuntimeConfig(provider, "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-model", "fake-only");
                var observed = new AtomicReference<ChatRequest>();
                ChatModelListener listener = new ChatModelListener() {
                    @Override public void onRequest(ChatModelRequestContext context) { observed.set(context.chatRequest()); }
                };
                var model = factory.createStreaming(config, ReasoningDepth.AUTO, new AgentStreamLease(), List.of(listener));
                String result = "synthetic-result-only-".repeat(200);
                List<ChatMessage> messages = new ArrayList<>();
                messages.add(UserMessage.from("original request"));
                for (int i = 0; i < 3; i++) {
                    String id = "call-" + i;
                    messages.add(AiMessage.from(ToolExecutionRequest.builder().id(id).name("list_learning_plans").arguments("{}").build()));
                    messages.add(ToolExecutionResultMessage.from(id, "list_learning_plans", result));
                }
                messages.add(UserMessage.from("latest correction"));
                var complete = new CompletableFuture<ChatResponse>();
                model.chat(messages, new StreamingChatResponseHandler() {
                    @Override public void onCompleteResponse(ChatResponse response) { complete.complete(response); }
                    @Override public void onError(Throwable error) { complete.completeExceptionally(error); }
                });
                assertThat(complete.get(8, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("ok");
                assertThat(observed.get()).isNotNull();
                var toolResults = observed.get().messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast).toList();
                assertThat(toolResults).extracting(ToolExecutionResultMessage::id).containsExactly("call-0", "call-1", "call-2");
                assertThat(toolResults.getFirst().text()).isEqualTo(result);
                assertThat(toolResults.get(1).text()).contains("call-0").doesNotContain("synthetic-result-only-");
                assertThat(toolResults.getLast().text()).isEqualTo(result);
                assertThat(captured.get()).contains("call-0", "call-1", "call-2", "latest correction", "synthetic-result-only-");
                assertThat(messages.stream().filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast).map(ToolExecutionResultMessage::text)).containsOnly(result);
            } finally { server.stop(0); }
        }
    }
}
