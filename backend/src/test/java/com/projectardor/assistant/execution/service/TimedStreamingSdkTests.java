package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.sun.net.httpserver.HttpServer;
import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

class TimedStreamingSdkTests {
    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void bothSdkProtocolsForwardSseContentAndPersistExactlyOneMeasuredCall() throws Exception {
        for (var provider : LlmProvider.values()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                String events = provider == LlmProvider.OPENAI_COMPATIBLE ? """
                        data: {"id":"fixture","object":"chat.completion.chunk","model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","content":"hello"}}]}

                        data: {"id":"fixture","object":"chat.completion.chunk","model":"fixture-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}

                        data: [DONE]

                        """ : """
                        event: message_start
                        data: {"type":"message_start","message":{"id":"fixture","type":"message","role":"assistant","model":"fixture-model","content":[],"usage":{"input_tokens":10,"output_tokens":0}}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}

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
                var store = mock(AgentTraceStore.class);
                when(store.enabled()).thenReturn(true);
                when(store.startSpan(any(), any(), any(), any(), any(), any())).thenAnswer(call -> UUID.randomUUID());
                var tracer = AgentRunTracer.begin(store, UUID.randomUUID(), UUID.randomUUID(), null, "fixture-model", "test");
                var config = new LlmConfigService.LlmRuntimeConfig(provider, "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-model", "fake-only");
                var factory = new LangChainModelFactory(mock(LlmConfigService.class), "", 0, Duration.ofSeconds(2), Duration.ofSeconds(5));
                var model = factory.createStreaming(config, ReasoningDepth.AUTO, new AgentStreamLease(), List.of(new AgentModelTraceListener(tracer, "fixture-model")));
                var completed = new CompletableFuture<ChatResponse>();
                var text = new StringBuilder();
                model.chat(List.of(UserMessage.from("synthetic")), new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String token) { text.append(token); }
                    @Override public void onCompleteResponse(ChatResponse response) { completed.complete(response); }
                    @Override public void onError(Throwable error) { completed.completeExceptionally(error); }
                });
                assertThat(completed.get(8, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("hello");
                assertThat(text.toString()).isEqualTo("hello");
                assertThat(tracer.llmCalls()).isEqualTo(1);
                ArgumentCaptor<Map> attributes = ArgumentCaptor.forClass(Map.class);
                ArgumentCaptor<Integer> cacheCreation = ArgumentCaptor.forClass(Integer.class);
                ArgumentCaptor<Integer> cacheRead = ArgumentCaptor.forClass(Integer.class);
                verify(store).finishSpan(any(), any(), eq("COMPLETED"), isNull(), any(), any(), eq(10), eq(2),
                        cacheCreation.capture(), cacheRead.capture(), attributes.capture());
                if (provider == LlmProvider.ANTHROPIC_COMPATIBLE) {
                    // The fixture reports no cached tokens; a reported zero is a fact, not an unknown.
                    assertThat(cacheCreation.getValue()).isZero();
                    assertThat(cacheRead.getValue()).isZero();
                } else {
                    assertThat(cacheCreation.getValue()).isNull();
                    assertThat(cacheRead.getValue()).isNull();
                }
                assertThat(attributes.getValue()).containsKeys("firstContentMs", "streamAfterFirstContentMs", "outputTokensPerRequestSecond")
                        .containsEntry("timingSource", "client-monotonic-stream-v1");
            } finally { server.stop(0); }
        }
    }
}
