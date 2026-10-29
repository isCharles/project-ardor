package com.projectardor.platform.model.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.projectardor.platform.model.llm.domain.LlmProvider;

import tools.jackson.databind.ObjectMapper;

class StreamingJsonTransportTests {

    private static final MediaType EVENT_STREAM = MediaType.valueOf("text/event-stream");
    private static final String CHAT_URL = "https://llm.example/chat/completions";

    private final UUID user = UUID.randomUUID();

    @Test void streamsDeltasInOrderAndReturnsTheWholeDocument() {
        Fixture fixture = fixture(LlmProvider.OPENAI_COMPATIBLE);
        fixture.server.expect(once(), requestTo(CHAT_URL)).andRespond(withSuccess("""
                data: {"model":"fixture","choices":[{"index":0,"delta":{"content":"请介绍一下"}}]}

                data: {"model":"fixture","choices":[{"index":0,"delta":{"content":"Redis 的过期策略。"}}]}

                data: {"model":"fixture","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """, EVENT_STREAM));
        List<String> deltas = new ArrayList<>();
        LlmGateway.LlmResult result = fixture.gateway.completeJsonStreaming(
                user, "system", "user", deltas::add, never());

        assertThat(deltas).containsExactly("请介绍一下", "Redis 的过期策略。");
        assertThat(result.content()).isEqualTo("请介绍一下Redis 的过期策略。");
        assertThat(result.model()).isEqualTo("fixture");
    }

    @Test void reasoningOnlyDeltasAreNotSpokenAsAnswerText() {
        Fixture fixture = fixture(LlmProvider.OPENAI_COMPATIBLE);
        fixture.server.expect(once(), requestTo(CHAT_URL)).andRespond(withSuccess("""
                data: {"choices":[{"index":0,"delta":{"reasoning_content":"内部思考不该朗读"}}]}

                data: {"choices":[{"index":0,"delta":{"content":"这是答案。"}}]}

                data: [DONE]

                """, EVENT_STREAM));
        List<String> deltas = new ArrayList<>();
        fixture.gateway.completeJsonStreaming(user, "system", "user", deltas::add, never());
        assertThat(deltas).containsExactly("这是答案。");
    }

    @Test void aTruncatedStreamIsRefusedExactlyLikeTheBlockingPath() {
        Fixture fixture = fixture(LlmProvider.OPENAI_COMPATIBLE);
        fixture.server.expect(once(), requestTo(CHAT_URL)).andRespond(withSuccess("""
                data: {"choices":[{"index":0,"delta":{"content":"{\\"questions\\":["}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

                data: [DONE]

                """, EVENT_STREAM));
        assertThatThrownBy(() -> fixture.gateway.completeJsonStreaming(
                user, "system", "user", delta -> { }, never()))
                .isInstanceOfSatisfying(LlmCallException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("LLM_OUTPUT_TRUNCATED"));
    }

    @Test void aCancelledStreamHasItsOwnFailureCode() {
        Fixture fixture = fixture(LlmProvider.OPENAI_COMPATIBLE);
        fixture.server.expect(once(), requestTo(CHAT_URL)).andRespond(withSuccess("""
                data: {"choices":[{"index":0,"delta":{"content":"第一段"}}]}

                data: {"choices":[{"index":0,"delta":{"content":"第二段"}}]}

                data: [DONE]

                """, EVENT_STREAM));
        AtomicBoolean firstDeltaSeen = new AtomicBoolean();
        BooleanSupplier stopAfterFirstDelta = () -> firstDeltaSeen.compareAndSet(false, true);
        assertThatThrownBy(() -> fixture.gateway.completeJsonStreaming(
                user, "system", "user", delta -> { }, stopAfterFirstDelta))
                .isInstanceOfSatisfying(LlmCallException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(JsonStreamProtocol.CANCELLED_CODE));
    }

    @Test void httpErrorsOnTheStreamUseTheSharedClassification() {
        Fixture fixture = fixture(LlmProvider.OPENAI_COMPATIBLE);
        fixture.server.expect(once(), requestTo(CHAT_URL)).andRespond(withUnauthorizedRequest());
        assertThatThrownBy(() -> fixture.gateway.completeJsonStreaming(
                user, "system", "user", delta -> { }, never()))
                .isInstanceOfSatisfying(LlmCallException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("LLM_AUTH_FAILED"));
    }

    @Test void providersWithoutAStreamingTransportStillDeliverTheWholeDocument() {
        Fixture fixture = fixture(LlmProvider.ANTHROPIC_COMPATIBLE);
        fixture.server.expect(once(), requestTo("https://llm.example/v1/messages")).andRespond(withSuccess(
                "{\"model\":\"fixture\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"questions\\\":[]}\"}]}",
                MediaType.APPLICATION_JSON));
        List<String> deltas = new ArrayList<>();
        LlmGateway.LlmResult result = fixture.gateway.completeJsonStreaming(
                user, "system", "user", deltas::add, never());
        assertThat(deltas).containsExactly("{\"questions\":[]}");
        assertThat(result.content()).isEqualTo("{\"questions\":[]}");
    }

    private static BooleanSupplier never() {
        return () -> false;
    }

    private Fixture fixture(LlmProvider provider) {
        LlmConfigService configService = mock(LlmConfigService.class);
        when(configService.getRuntimeConfig(user)).thenReturn(new LlmConfigService.LlmRuntimeConfig(
                provider, "https://llm.example", "fixture-model", "test-key"));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper();
        CompatibleLlmGateway gateway = new CompatibleLlmGateway(configService,
                List.of(new OpenAiJsonModelProtocol(mapper, builder),
                        new AnthropicJsonModelProtocol(mapper, builder)),
                List.of(new OpenAiJsonStreamProtocol(mapper, builder)),
                new ModelCallCapacity(8));
        return new Fixture(gateway, server);
    }

    private record Fixture(CompatibleLlmGateway gateway, MockRestServiceServer server) {
    }
}
