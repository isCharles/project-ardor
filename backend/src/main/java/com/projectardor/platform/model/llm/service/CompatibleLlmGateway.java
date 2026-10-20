package com.projectardor.platform.model.llm.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import tools.jackson.databind.ObjectMapper;

@Component
public class CompatibleLlmGateway implements LlmGateway {

    private final LlmConfigService configService;
    private final ModelProtocols<JsonModelProtocol> protocols;

    @org.springframework.beans.factory.annotation.Autowired
    public CompatibleLlmGateway(LlmConfigService configService,List<JsonModelProtocol> protocols) {
        this.configService=configService;this.protocols=new ModelProtocols<>(protocols);
    }

    /** Compatibility for callers constructing the default transports outside Spring. */
    public CompatibleLlmGateway(LlmConfigService configService,ObjectMapper mapper,RestClient.Builder clients) {
        this(configService,List.of(new OpenAiJsonModelProtocol(mapper,clients),new AnthropicJsonModelProtocol(mapper,clients)));
    }

    @Override
    public LlmResult completeJson(UUID userId, String systemPrompt, String userPrompt) {
        return completeJson(configService.getRuntimeConfig(userId), systemPrompt, userPrompt);
    }

    public LlmResult completeJson(
            LlmConfigService.LlmRuntimeConfig config,
            String systemPrompt,
            String userPrompt) {
        try {
            return protocols.require(config.provider()).complete(config,systemPrompt,userPrompt);
        } catch (RestClientResponseException exception) {
            throw httpFailure(exception);
        } catch (ResourceAccessException exception) {
            throw accessFailure(exception);
        } catch (LlmCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LlmCallException(
                    "LLM_INVALID_RESPONSE",
                    "LLM 返回格式无法解析，请检查所选协议是否与 Base URL 匹配",
                    false,
                    exception);
        }
    }

    private LlmCallException httpFailure(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return switch (status) {
            case 400 -> new LlmCallException(
                    "LLM_BAD_REQUEST", "LLM 拒绝请求，请检查模型名称和协议", false, exception);
            case 401, 403 -> new LlmCallException(
                    "LLM_AUTH_FAILED", "LLM 鉴权失败，请检查 API Key", false, exception);
            case 404 -> new LlmCallException(
                    "LLM_NOT_FOUND", "LLM 接口或模型不存在，请检查 Base URL、协议和模型名称", false, exception);
            case 408, 429 -> new LlmCallException(
                    "LLM_BUSY", "LLM 服务繁忙或触发限流，请稍后重试", true, exception);
            default -> new LlmCallException(
                    "LLM_HTTP_ERROR",
                    "LLM 请求失败（HTTP " + status + "）",
                    status >= 500,
                    exception);
        };
    }

    private LlmCallException accessFailure(ResourceAccessException exception) {
        Throwable root = rootCause(exception);
        if (root instanceof UnknownHostException) {
            return new LlmCallException(
                    "LLM_DNS_FAILED", "无法解析 LLM 域名，请检查 Docker DNS 或代理设置", true, exception);
        }
        if (root instanceof SocketTimeoutException) {
            return new LlmCallException(
                    "LLM_TIMEOUT", "连接 LLM 超时，请检查网络、代理或服务状态", true, exception);
        }
        if (root instanceof ConnectException) {
            return new LlmCallException(
                    "LLM_CONNECT_FAILED", "无法连接 LLM 服务，请检查 Base URL、网络或代理", true, exception);
        }
        if (root instanceof SSLException) {
            return new LlmCallException(
                    "LLM_TLS_FAILED", "LLM TLS 连接失败，请检查代理与证书环境", true, exception);
        }
        return new LlmCallException(
                "LLM_NETWORK_FAILED", "LLM 网络请求失败，请检查网络或代理设置", true, exception);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
