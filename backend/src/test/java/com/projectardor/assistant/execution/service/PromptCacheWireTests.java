package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService.LlmRuntimeConfig;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PromptCacheWireTests {

    private static final String RESPONSE = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-fixture",
             "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":10,"output_tokens":2,
                      "cache_creation_input_tokens":1200,"cache_read_input_tokens":5000}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void cachesToolDefinitionsButLeavesTheDynamicSystemPromptUncached() {
        model(true).chat(request());

        JsonNode body = mapper.readTree(lastBody.get());
        JsonNode tools = body.path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools.get(tools.size() - 1).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(body.path("system").toString()).doesNotContain("cache_control");
        assertThat(countOccurrences(body.toString(), "cache_control")).isEqualTo(1);
    }

    @Test
    void sendsNoCacheControlWhenPromptCacheIsDisabled() {
        model(false).chat(request());

        assertThat(lastBody.get()).doesNotContain("cache_control");
    }

    @Test
    void exposesCachedTokenCountsFromTheResponse() {
        ChatResponse response = model(true).chat(request());

        LlmUsage usage = LlmUsage.of(response.tokenUsage());
        assertThat(response.tokenUsage()).isInstanceOf(AnthropicTokenUsage.class);
        assertThat(usage.inputTokens()).isEqualTo(10);
        assertThat(usage.outputTokens()).isEqualTo(2);
        assertThat(usage.cacheCreationInputTokens()).isEqualTo(1_200);
        assertThat(usage.cacheReadInputTokens()).isEqualTo(5_000);
    }

    private ChatModel model(boolean promptCache) {
        return new AnthropicAgentModelProtocol(promptCache, true)
                .chat(new LlmRuntimeConfig(LlmProvider.ANTHROPIC_COMPATIBLE, baseUrl, "claude-fixture", "test-key"),
                        new ModelReasoningPolicy.Profile(null, Map.of(), null, null, 512, false, false),
                        new JdkHttpClientBuilder(), Duration.ofSeconds(20), 0);
    }

    private static ChatRequest request() {
        return ChatRequest.builder()
                .messages(List.of(SystemMessage.from("static prompt"), UserMessage.from("synthetic question")))
                .toolSpecifications(List.of(ToolSpecification.builder().name("list_calendars")
                        .description("List the caller's calendars")
                        .parameters(JsonObjectSchema.builder().addStringProperty("timezone", "IANA zone").build())
                        .build()))
                .build();
    }

    private static int countOccurrences(String haystack, String needle) {
        int index = 0;
        int total = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            total++;
            index += needle.length();
        }
        return total;
    }
}
