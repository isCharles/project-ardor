package com.projectardor.websearch.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.projectardor.websearch.web.WebSearchConfigRequest;
import com.projectardor.websearch.web.WebSearchTestResponse;

@Service
public class TavilySearchService {

    private final WebSearchConfigService configService;
    private final RestClient client;

    public TavilySearchService(WebSearchConfigService configService, RestClient.Builder builder) {
        this.configService = configService;
        this.client = builder.baseUrl("https://api.tavily.com").build();
    }

    public WebSearchResult search(UUID userId, String rawQuery) {
        String query = normalizeQuery(rawQuery);
        return execute(configService.requireApiKey(userId), query, 5);
    }

    public WebSearchTestResponse test(UUID userId, WebSearchConfigRequest request) {
        long startedAt = System.nanoTime();
        try {
            TavilyUsageResponse response = client.get()
                    .uri("/usage")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + configService.apiKeyForTest(userId, request))
                    .retrieve()
                    .body(TavilyUsageResponse.class);
            if (response == null || response.key() == null) {
                throw new IllegalStateException("Tavily 已连接，但没有返回用量信息");
            }
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            return new WebSearchTestResponse(
                    true,
                    "Tavily 连接与 API Key 认证成功",
                    latencyMs,
                    response.key().usage(),
                    response.key().limit());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                throw new IllegalArgumentException("Tavily API Key 无效或已失效");
            }
            if (exception.getStatusCode().value() == 429) {
                throw new IllegalStateException("Tavily 请求过于频繁，请稍后再试");
            }
            throw new IllegalStateException("Tavily 连接测试失败（HTTP " + exception.getStatusCode().value() + "）");
        } catch (RestClientException exception) {
            throw new IllegalStateException("无法连接 Tavily，请检查服务端网络或代理设置");
        }
    }

    private WebSearchResult execute(String apiKey, String query, int maxResults) {
        try {
            TavilyApiResponse response = client.post()
                    .uri("/search")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(Map.of(
                            "query", query,
                            "topic", "general",
                            "search_depth", "basic",
                            "max_results", maxResults,
                            "include_answer", false,
                            "include_raw_content", false,
                            "include_images", false,
                            "include_usage", true))
                    .retrieve()
                    .body(TavilyApiResponse.class);
            if (response == null) throw new IllegalStateException("Tavily 没有返回可用结果");
            List<WebSearchResult.ResultItem> results = response.results() == null ? List.of() : response.results().stream()
                    .map(item -> new WebSearchResult.ResultItem(
                            item.title(), item.url(), item.content(), item.score() == null ? 0 : item.score()))
                    .toList();
            return new WebSearchResult(
                    response.query() == null ? query : response.query(),
                    results,
                    response.usage() == null ? null : response.usage().credits(),
                    response.responseTime());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                throw new IllegalArgumentException("Tavily API Key 无效或已失效");
            }
            if (exception.getStatusCode().value() == 429) {
                throw new IllegalStateException("Tavily 请求过于频繁，请稍后再试");
            }
            throw new IllegalStateException("Tavily 搜索失败（HTTP " + exception.getStatusCode().value() + "）");
        } catch (RestClientException exception) {
            throw new IllegalStateException("无法连接 Tavily，请检查服务端网络或代理设置");
        }
    }

    private String normalizeQuery(String value) {
        String query = value == null ? "" : value.strip();
        if (query.isBlank()) throw new IllegalArgumentException("搜索关键词不能为空");
        if (query.length() > 400) throw new IllegalArgumentException("搜索关键词不能超过 400 个字符");
        return query;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TavilyApiResponse(
            String query,
            List<TavilyResultItem> results,
            TavilyUsage usage,
            @JsonProperty("response_time") String responseTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TavilyResultItem(String title, String url, String content, Double score) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TavilyUsage(Integer credits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TavilyUsageResponse(TavilyKeyUsage key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TavilyKeyUsage(Integer usage, Integer limit) {
    }
}
