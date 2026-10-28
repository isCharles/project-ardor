package com.projectardor.platform.model.llm.service;

import java.util.List;
import java.util.UUID;


import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import com.projectardor.assistant.execution.service.AgentRequestTokens;
import com.projectardor.assistant.execution.service.ModelConfigFingerprint;

import tools.jackson.databind.ObjectMapper;

@Component
public class CompatibleLlmGateway implements LlmGateway {

    private final LlmConfigService configService;
    private final ModelProtocols<JsonModelProtocol> protocols;
    private final ModelCallCapacity capacity;

    @org.springframework.beans.factory.annotation.Autowired
    public CompatibleLlmGateway(LlmConfigService configService,List<JsonModelProtocol> protocols,
            ModelCallCapacity capacity) {
        this.configService=configService;this.protocols=new ModelProtocols<>(protocols);this.capacity=capacity;
    }

    /** Compatibility for protocol tests that do not exercise the shared capacity guard. */
    public CompatibleLlmGateway(LlmConfigService configService,List<JsonModelProtocol> protocols) {
        this(configService, protocols, new ModelCallCapacity(8));
    }

    /** Compatibility for callers constructing the default transports outside Spring. */
    public CompatibleLlmGateway(LlmConfigService configService,ObjectMapper mapper,RestClient.Builder clients) {
        this(configService,List.of(new OpenAiJsonModelProtocol(mapper,clients),new AnthropicJsonModelProtocol(mapper,clients)),
                new ModelCallCapacity(8));
    }

    @Override
    public LlmResult completeJson(UUID userId, String systemPrompt, String userPrompt) {
        return completeJson(userId, configService.getRuntimeConfig(userId), systemPrompt, userPrompt);
    }

    public LlmResult completeJson(
            LlmConfigService.LlmRuntimeConfig config,
            String systemPrompt,
            String userPrompt) {
        return completeJson(null, config, systemPrompt, userPrompt);
    }

    public LlmResult completeJson(UUID userId, LlmConfigService.LlmRuntimeConfig config,
            String systemPrompt, String userPrompt) {
        var estimator = AgentRequestTokens.estimator(config);
        long tokens = estimator.estimateTokenCountInText(systemPrompt)
                + estimator.estimateTokenCountInText(userPrompt);
        if (!capacity.tryAcquire("job", userId, ModelConfigFingerprint.of(config), tokens)) {
            throw capacity.busy();
        }
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
        } finally {
            capacity.release();
        }
    }

    private LlmCallException httpFailure(RestClientResponseException exception) {
        return LlmFailureClassifier.http(exception.getStatusCode().value(), exception);
    }

    private LlmCallException accessFailure(ResourceAccessException exception) {
        LlmCallException payload = LlmFailureClassifier.payload(exception);
        if (payload != null) return payload;
        LlmCallException classified = LlmFailureClassifier.transport(exception);
        if (classified != null) return classified;
        return new LlmCallException(
                "LLM_NETWORK_FAILED", "LLM 网络请求失败，请检查网络或代理设置", true, exception);
    }

}
