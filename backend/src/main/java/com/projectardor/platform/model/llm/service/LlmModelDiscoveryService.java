package com.projectardor.platform.model.llm.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.projectardor.common.security.ExternalBaseUrlPolicy;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.web.LlmModelListRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Lists model IDs exposed by an OpenAI-compatible (or Anthropic-compatible) endpoint. */
@Service
public class LlmModelDiscoveryService {

    private static final int MAX_MODELS = 500;

    private final LlmConfigService configService;
    private final ExternalBaseUrlPolicy externalBaseUrlPolicy;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    public LlmModelDiscoveryService(
            LlmConfigService configService,
            ExternalBaseUrlPolicy externalBaseUrlPolicy,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.configService = configService;
        this.externalBaseUrlPolicy = externalBaseUrlPolicy;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
    }

    public List<String> list(UUID userId, LlmModelListRequest request) {
        String baseUrl = externalBaseUrlPolicy.normalizeAndValidate(request.baseUrl());
        String apiKey = configService.apiKeyForTest(userId, request.apiKey());
        String payload;
        try {
            payload = restClientBuilder.clone().baseUrl(baseUrl).build().get()
                    .uri("/models")
                    .headers(headers -> {
                        headers.setBearerAuth(apiKey);
                        headers.set("x-api-key", apiKey);
                        headers.set("anthropic-version", "2023-06-01");
                    })
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalArgumentException("API Key 无效、权限不足，或认证方式不匹配");
            }
            if (status == 404 || status == 405) {
                throw new IllegalStateException("该服务不支持标准 /models 探测；请核对 Base URL，或手动填写模型名称");
            }
            if (status == 429) {
                throw new IllegalStateException("服务请求过于频繁，请稍后再试");
            }
            throw new IllegalStateException("获取模型列表失败（HTTP " + status + "）");
        } catch (RestClientException exception) {
            throw new IllegalStateException("无法连接该服务，请检查 Base URL、服务端网络或代理设置");
        }
        return parseModels(payload, request.provider());
    }

    private List<String> parseModels(String payload, LlmProvider provider) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalStateException("服务没有返回模型列表");
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode entries = root.path("data");
            if (!entries.isArray()) entries = root.path("models");
            if (!entries.isArray()) {
                throw new IllegalStateException("服务返回格式不是标准模型列表；请手动填写模型名称");
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (JsonNode entry : entries) {
                String id = entry.isTextual() ? entry.asText() : entry.path("id").asText("");
                if (!id.isBlank()) ids.add(id);
                if (ids.size() >= MAX_MODELS) break;
            }
            if (ids.isEmpty()) {
                throw new IllegalStateException("服务没有返回可用模型；请核对令牌分组或 Base URL");
            }
            List<String> models = new ArrayList<>(ids);
            models.sort(String.CASE_INSENSITIVE_ORDER);
            return models;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("无法解析模型列表；请手动填写模型名称");
        }
    }
}
