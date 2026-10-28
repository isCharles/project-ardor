package com.projectardor.platform.model.llm.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import com.projectardor.assistant.execution.service.AgentRequestTokens;
import com.projectardor.assistant.execution.service.ModelConfigFingerprint;
import com.projectardor.platform.model.llm.domain.LlmProvider;

import tools.jackson.databind.ObjectMapper;

@Component
public class CompatibleLlmGateway implements LlmGateway {

    private final LlmConfigService configService;
    private final ModelProtocols<JsonModelProtocol> protocols;
    private final Map<LlmProvider, JsonStreamProtocol> streamProtocols;
    private final ModelCallCapacity capacity;

    @org.springframework.beans.factory.annotation.Autowired
    public CompatibleLlmGateway(LlmConfigService configService, List<JsonModelProtocol> protocols,
            List<JsonStreamProtocol> streamProtocols, ModelCallCapacity capacity) {
        this.configService = configService;
        this.protocols = new ModelProtocols<>(protocols);
        this.streamProtocols = register(streamProtocols);
        this.capacity = capacity;
    }

    /** Compatibility for protocol tests that do not exercise the shared capacity guard. */
    public CompatibleLlmGateway(LlmConfigService configService, List<JsonModelProtocol> protocols) {
        this(configService, protocols, List.of(), new ModelCallCapacity(8));
    }

    /** Compatibility for callers constructing the default transports outside Spring. */
    public CompatibleLlmGateway(LlmConfigService configService, ObjectMapper mapper, RestClient.Builder clients) {
        this(configService, List.of(new OpenAiJsonModelProtocol(mapper, clients),
                new AnthropicJsonModelProtocol(mapper, clients)), List.of(), new ModelCallCapacity(8));
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
            return protocols.require(config.provider()).complete(config, systemPrompt, userPrompt);
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

    @Override
    public LlmResult completeJsonStreaming(
            UUID userId,
            String systemPrompt,
            String userPrompt,
            Consumer<String> onDelta,
            BooleanSupplier stopRequested) {
        return completeJsonStreaming(userId, configService.getRuntimeConfig(userId),
                systemPrompt, userPrompt, onDelta, stopRequested);
    }

    public LlmResult completeJsonStreaming(
            UUID userId,
            LlmConfigService.LlmRuntimeConfig config,
            String systemPrompt,
            String userPrompt,
            Consumer<String> onDelta,
            BooleanSupplier stopRequested) {
        JsonStreamProtocol protocol = streamProtocols.get(config.provider());
        if (protocol == null) {
            LlmResult result = completeJson(userId, config, systemPrompt, userPrompt);
            if (!stopRequested.getAsBoolean()) onDelta.accept(result.content());
            return result;
        }
        var estimator = AgentRequestTokens.estimator(config);
        long tokens = estimator.estimateTokenCountInText(systemPrompt)
                + estimator.estimateTokenCountInText(userPrompt);
        if (!capacity.tryAcquire("stream", userId, ModelConfigFingerprint.of(config), tokens)) {
            throw capacity.busy();
        }
        try {
            return protocol.stream(config, systemPrompt, userPrompt, onDelta, stopRequested);
        } catch (RestClientResponseException exception) {
            throw httpFailure(exception);
        } catch (ResourceAccessException exception) {
            throw accessFailure(exception);
        } catch (LlmCallException exception) {
            throw exception;
        } catch (Exception exception) {
            LlmCallException payload = LlmFailureClassifier.payload(exception);
            if (payload != null) throw payload;
            LlmCallException transport = LlmFailureClassifier.transport(exception);
            if (transport != null) throw transport;
            throw new LlmCallException(
                    "LLM_INVALID_RESPONSE", "LLM 流式返回无法解析，请检查所选协议是否与 Base URL 匹配", false, exception);
        } finally {
            capacity.release();
        }
    }

    private static Map<LlmProvider, JsonStreamProtocol> register(List<JsonStreamProtocol> implementations) {
        Map<LlmProvider, JsonStreamProtocol> registered = new EnumMap<>(LlmProvider.class);
        for (JsonStreamProtocol protocol : implementations) {
            if (protocol == null || protocol.provider() == null) continue;
            registered.putIfAbsent(protocol.provider(), protocol);
        }
        return registered;
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
