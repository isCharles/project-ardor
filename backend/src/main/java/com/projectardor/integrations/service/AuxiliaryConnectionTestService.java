package com.projectardor.integrations.service;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.projectardor.integrations.domain.AuxiliaryServiceType;
import com.projectardor.integrations.web.AuxiliaryApiConfigRequest;
import com.projectardor.integrations.web.AuxiliaryConnectionTestResponse;

@Service
public class AuxiliaryConnectionTestService {

    private final AuxiliaryApiConfigService configService;
    private final RestClient.Builder restClientBuilder;

    public AuxiliaryConnectionTestService(
            AuxiliaryApiConfigService configService,
            RestClient.Builder restClientBuilder) {
        this.configService = configService;
        this.restClientBuilder = restClientBuilder;
    }

    public AuxiliaryConnectionTestResponse test(
            UUID userId,
            AuxiliaryServiceType serviceType,
            AuxiliaryApiConfigRequest request) {
        AuxiliaryApiConfigService.AuxiliaryRuntimeConfig config =
                configService.runtimeConfigForTest(userId, serviceType, request);
        long startedAt = System.nanoTime();
        try {
            restClientBuilder.clone().baseUrl(config.baseUrl()).build().get()
                    .uri("/models")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .retrieve()
                    .toBodilessEntity();
            return success(startedAt, "连接与 API Key 认证成功（未执行模型）");
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalArgumentException("API Key 无效、权限不足，或认证方式不匹配");
            }
            if (status == 404 || status == 405) {
                throw new IllegalStateException("服务可以访问，但不支持标准 /models 探测；请核对 Base URL");
            }
            if (status == 429) {
                throw new IllegalStateException("服务请求过于频繁，请稍后再试");
            }
            throw new IllegalStateException("连接测试失败（HTTP " + status + "）");
        } catch (RestClientException exception) {
            throw new IllegalStateException("无法连接该服务，请检查 Base URL、服务端网络或代理设置");
        }
    }

    private AuxiliaryConnectionTestResponse success(long startedAt, String message) {
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new AuxiliaryConnectionTestResponse(true, message, latencyMs);
    }
}
