package com.projectardor.platform.model.llm.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.projectardor.platform.model.llm.web.LlmConfigUpdateRequest;
import com.projectardor.platform.model.llm.web.LlmConnectionTestResponse;

/**
 * Verifies that a configured LLM connection can answer JSON and call a tool.
 *
 * <p>The model/platform layer owns the JSON probe. Tool calling needs the
 * assistant's chat-model factory and tool schema, so it is delegated through
 * {@link ToolCallingProbe} to keep the platform package free of business
 * imports.</p>
 */
@Service
public class LlmConnectionTestService {

    private final LlmConfigService configService;
    private final CompatibleLlmGateway gateway;
    private final ToolCallingProbe toolCallingProbe;

    public LlmConnectionTestService(
            LlmConfigService configService,
            CompatibleLlmGateway gateway,
            ToolCallingProbe toolCallingProbe) {
        this.configService = configService;
        this.gateway = gateway;
        this.toolCallingProbe = toolCallingProbe;
    }

    public LlmConnectionTestResponse test(UUID userId, LlmConfigUpdateRequest request) {
        var config = configService.runtimeConfigForTest(userId, request);
        return test(userId, config);
    }

    public LlmConnectionTestResponse test(UUID userId, LlmConfigService.LlmRuntimeConfig config) {
        long startedAt = System.nanoTime();
        LlmGateway.LlmResult result = gateway.completeJson(
                config,
                "你是 API 连通性测试助手。请用一句简短中文回答。",
                "回复：连接成功");
        toolCallingProbe.verify(config, userId);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new LlmConnectionTestResponse(
                true, config.provider(), result.model(), latencyMs, "连接成功，Tool Calling 可用");
    }
}
