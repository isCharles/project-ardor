package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

class PromptCacheUsageTests {

    @Test
    void readsAnthropicCacheTokensSeparatelyFromInput() {
        AnthropicTokenUsage usage = AnthropicTokenUsage.builder()
                .inputTokenCount(120).outputTokenCount(40)
                .cacheCreationInputTokens(2_000).cacheReadInputTokens(9_000).build();

        LlmUsage mapped = LlmUsage.of(usage);

        assertThat(mapped.inputTokens()).isEqualTo(120);
        assertThat(mapped.outputTokens()).isEqualTo(40);
        assertThat(mapped.cacheCreationInputTokens()).isEqualTo(2_000);
        assertThat(mapped.cacheReadInputTokens()).isEqualTo(9_000);
    }

    @Test
    void keepsCacheUsageUnknownWhenProviderOmitsIt() {
        assertThat(LlmUsage.of(new TokenUsage(10, 2)).cacheReadInputTokens()).isNull();
        assertThat(LlmUsage.of(new TokenUsage(10, 2)).cacheCreationInputTokens()).isNull();
        assertThat(LlmUsage.of(null).inputTokens()).isNull();

        AnthropicTokenUsage partial = AnthropicTokenUsage.builder().inputTokenCount(10).outputTokenCount(2).build();
        assertThat(LlmUsage.of(partial).hasCacheFields()).isFalse();
    }

    @Test
    void listenerStoresCacheTokensOnSpanAndRunSummary() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        when(store.enabled()).thenReturn(true);
        UUID user = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        UUID llmSpan = UUID.randomUUID();
        when(store.startSpan(eq(user), eq(run), isNull(), eq("RUN"), eq("agent.run"), any())).thenReturn(root);
        AgentRunTracer tracer = AgentRunTracer.begin(store, user, run, null, "fixture-model", "v1");
        when(store.startSpan(eq(user), eq(run), eq(tracer.rootSpanId()), eq("LLM"), eq("llm.call.1"), any()))
                .thenReturn(llmSpan);
        AgentModelTraceListener listener = new AgentModelTraceListener(tracer, "fixture-model");
        Map<Object, Object> attributes = new java.util.HashMap<>();
        ChatModelRequestContext request = mock(ChatModelRequestContext.class);
        when(request.attributes()).thenReturn(attributes);
        when(request.chatRequest()).thenReturn(null);
        when(request.modelProvider()).thenReturn(null);
        listener.onRequest(request);

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .modelName("fixture-model")
                .tokenUsage(AnthropicTokenUsage.builder().inputTokenCount(150).outputTokenCount(30)
                        .cacheCreationInputTokens(2_000).cacheReadInputTokens(9_000).build())
                .build();
        dev.langchain4j.model.chat.listener.ChatModelResponseContext responseContext =
                mock(dev.langchain4j.model.chat.listener.ChatModelResponseContext.class);
        when(responseContext.attributes()).thenReturn(attributes);
        when(responseContext.chatResponse()).thenReturn(response);
        listener.onResponse(responseContext);

        assertThat(tracer.inputTokens()).isEqualTo(150);
        assertThat(tracer.cacheReadInputTokens()).isEqualTo(9_000);
        assertThat(tracer.cacheCreationInputTokens()).isEqualTo(2_000);
        assertThat(tracer.cacheUsageMissingCalls()).isZero();

        ArgumentCaptor<Map> spanAttributes = ArgumentCaptor.forClass(Map.class);
        verify(store).finishSpan(eq(user), eq(llmSpan), eq("COMPLETED"), isNull(), eq("fixture-model"),
                eq("v1"), eq(150), eq(30), eq(2_000), eq(9_000), spanAttributes.capture());
        assertThat(spanAttributes.getValue()).containsEntry("cacheReadInputTokens", 9_000)
                .containsEntry("cacheCreationInputTokens", 2_000);

        tracer.complete();
        ArgumentCaptor<Map> summary = ArgumentCaptor.forClass(Map.class);
        verify(store).finishSpan(eq(user), eq(root), eq("COMPLETED"), isNull(), eq("fixture-model"),
                eq("v1"), eq(150), eq(30), eq(2_000), eq(9_000), summary.capture());
        assertThat(summary.getValue()).containsEntry("cacheReadInputTokens", 9_000L)
                .containsEntry("cacheCreationInputTokens", 2_000L)
                .containsEntry("cacheUsageMissingCalls", 0);
        verify(store).updateRunAggregates(user, run, 1, 0, 0, 150, 30, 2_000L, 9_000L, true,
                "fixture-model", "v1");
    }

    @Test
    void unknownCacheUsageIsNotCountedAsZero() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        when(store.enabled()).thenReturn(true);
        UUID user = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        when(store.startSpan(eq(user), eq(run), isNull(), eq("RUN"), eq("agent.run"), any())).thenReturn(root);
        AgentRunTracer tracer = AgentRunTracer.begin(store, user, run, null, "fixture-model", "v1");

        tracer.recordLlm("ASSISTANT", new LlmUsage(10, 2, null, null), false, 0);
        tracer.complete();

        assertThat(tracer.cacheUsageReported()).isFalse();
        assertThat(tracer.cacheUsageMissingCalls()).isEqualTo(1);
        verify(store).updateRunAggregates(user, run, 1, 0, 0, 10, 2, 0L, 0L, false, "fixture-model", "v1");
    }

    @Test
    void fallsBackToUncachedRequestWhenRelayRejectsCacheControl() {
        String fingerprint = "fallback-test-" + UUID.randomUUID();
        AtomicInteger cachedCalls = new AtomicInteger();
        AtomicInteger plainCalls = new AtomicInteger();
        ChatModel cached = failingModel(cachedCalls, "400 Bad Request: Unsupported parameter: cache_control");
        ChatModel plain = respondingModel(plainCalls);

        ChatModel model = PromptCacheFallbackModels.chat(cached, () -> plain, fingerprint);
        ChatResponse first = model.chat(ChatRequest.builder().messages(UserMessage.from("synthetic")).build());
        ChatResponse second = model.chat(ChatRequest.builder().messages(UserMessage.from("synthetic")).build());

        assertThat(first.aiMessage().text()).isEqualTo("ok");
        assertThat(second.aiMessage().text()).isEqualTo("ok");
        assertThat(cachedCalls).hasValue(1);
        assertThat(plainCalls).hasValue(2);
    }

    @Test
    void doesNotRetryForErrorsUnrelatedToCache() {
        String fingerprint = "non-cache-test-" + UUID.randomUUID();
        AtomicInteger cachedCalls = new AtomicInteger();
        AtomicInteger plainCalls = new AtomicInteger();
        ChatModel cached = failingModel(cachedCalls, "401 Unauthorized");
        ChatModel plain = respondingModel(plainCalls);

        ChatModel model = PromptCacheFallbackModels.chat(cached, () -> plain, fingerprint);
        try {
            model.chat(ChatRequest.builder().messages(UserMessage.from("synthetic")).build());
        } catch (RuntimeException expected) {
            assertThat(expected).hasMessageContaining("401");
        }

        assertThat(cachedCalls).hasValue(1);
        assertThat(plainCalls).hasValue(0);
    }

    private static ChatModel failingModel(AtomicInteger calls, String message) {
        return new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                throw new IllegalArgumentException(message);
            }
            @Override public List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() { return List.of(); }
        };
    }

    private static ChatModel respondingModel(AtomicInteger calls) {
        return new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            }
            @Override public List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() { return List.of(); }
        };
    }
}
