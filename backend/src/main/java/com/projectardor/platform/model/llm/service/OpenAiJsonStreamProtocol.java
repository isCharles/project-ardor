package com.projectardor.platform.model.llm.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.projectardor.platform.model.llm.domain.LlmProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** OpenAI-compatible {@code stream:true} chat completion, read as server-sent events. */
@Component
public class OpenAiJsonStreamProtocol implements JsonStreamProtocol {

    private static final int MAX_ERROR_BODY = 400;

    private final ObjectMapper mapper;
    private final RestClient.Builder clients;

    public OpenAiJsonStreamProtocol(ObjectMapper mapper, RestClient.Builder clients) {
        this.mapper = mapper;
        this.clients = clients;
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.OPENAI_COMPATIBLE;
    }

    @Override
    public LlmGateway.LlmResult stream(
            LlmConfigService.LlmRuntimeConfig config,
            String systemPrompt,
            String userPrompt,
            Consumer<String> onDelta,
            BooleanSupplier stopRequested) throws Exception {
        StringBuilder content = new StringBuilder();
        State state = new State(config.model(), "");
        clients.clone().defaultHeader("Authorization", "Bearer " + config.apiKey()).build()
                .post().uri(config.baseUrl() + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(Map.of(
                        "model", config.model(),
                        "temperature", 0.2,
                        "stream", true,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt))))
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status < 200 || status >= 300) {
                        throw LlmFailureClassifier.http(status, new IOException(
                                "LLM 流式请求返回 HTTP " + status + ": " + errorBody(response.getBody())));
                    }
                    readEvents(response.getBody(), content, state, onDelta, stopRequested);
                    return null;
                });
        if ("length".equals(state.finishReason)) {
            throw new LlmCallException("LLM_OUTPUT_TRUNCATED", "模型输出达到长度上限，未保存不完整结果", false, null);
        }
        return JsonModelProtocol.result(content.toString(), state.model);
    }

    private void readEvents(
            InputStream body,
            StringBuilder content,
            State state,
            Consumer<String> onDelta,
            BooleanSupplier stopRequested) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String payload = line.substring("data:".length()).strip();
            if (payload.isEmpty()) continue;
            if ("[DONE]".equals(payload)) break;
            JsonNode chunk = mapper.readTree(payload);
            String reportedModel = chunk.path("model").asText("");
            if (!reportedModel.isBlank()) state.model = reportedModel;
            JsonNode choice = chunk.path("choices").path(0);
            JsonNode delta = choice.path("delta").path("content");
            if (delta.isTextual() && !delta.asText().isEmpty()) {
                content.append(delta.asText());
                onDelta.accept(delta.asText());
            }
            String reason = choice.path("finish_reason").asText("");
            if (!reason.isBlank()) state.finishReason = reason;
            if (stopRequested.getAsBoolean()) throw cancelled();
        }
        if (stopRequested.getAsBoolean()) throw cancelled();
    }

    private static LlmCallException cancelled() {
        return new LlmCallException(JsonStreamProtocol.CANCELLED_CODE, "模型流式输出已取消", false, null);
    }

    private String errorBody(InputStream body) {
        if (body == null) return "";
        try {
            byte[] bytes = body.readNBytes(MAX_ERROR_BODY);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            return "";
        }
    }

    private static final class State {
        private String model;
        private String finishReason;

        private State(String model, String finishReason) {
            this.model = model;
            this.finishReason = finishReason;
        }
    }
}
