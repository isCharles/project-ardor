package com.projectardor.llm.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import com.projectardor.agent.service.LangChainModelFactory;
import com.projectardor.agent.tools.CareerAgentTools;
import com.projectardor.llm.web.LlmConfigUpdateRequest;
import com.projectardor.llm.web.LlmConnectionTestResponse;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;

@Service
public class LlmConnectionTestService {

    private final LlmConfigService configService;
    private final CompatibleLlmGateway gateway;
    private final LangChainModelFactory modelFactory;
    private final CareerAgentTools careerAgentTools;

    public LlmConnectionTestService(
            LlmConfigService configService,
            CompatibleLlmGateway gateway,
            LangChainModelFactory modelFactory,
            CareerAgentTools careerAgentTools) {
        this.configService = configService;
        this.gateway = gateway;
        this.modelFactory = modelFactory;
        this.careerAgentTools = careerAgentTools;
    }

    public LlmConnectionTestResponse test(UUID userId, LlmConfigUpdateRequest request) {
        var config = configService.runtimeConfigForTest(userId, request);
        long startedAt = System.nanoTime();
        LlmGateway.LlmResult result = gateway.completeJson(
                config,
                "你是 API 连通性测试助手。请用一句简短中文回答。",
                "回复：连接成功");
        verifyToolCalling(config, userId);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new LlmConnectionTestResponse(
                true, config.provider(), result.model(), latencyMs, "连接成功，Tool Calling 可用");
    }

    private void verifyToolCalling(LlmConfigService.LlmRuntimeConfig config, UUID userId) {
        ToolProbe probe = new ToolProbe();
        try {
            Map<ToolSpecification, ToolExecutor> schemaProbeTools = new LinkedHashMap<>();
            ToolExecutor noOpExecutor = (request, memoryId) -> "compatibility-schema-ok";
                    ToolSpecifications.toolSpecificationsFrom(careerAgentTools.bind(userId, "连接测试"))
                    .forEach(specification -> schemaProbeTools.put(specification, noOpExecutor));
            ToolSpecifications.toolSpecificationsFrom(probe).forEach(specification ->
                    schemaProbeTools.put(specification, (request, memoryId) -> {
                        probe.called.set(true);
                        return "tool-calling-ok";
                    }));
            ProbeAssistant assistant = AiServices.builder(ProbeAssistant.class)
                    .chatModel(modelFactory.create(config))
                    .systemMessage("你正在进行兼容性测试。必须且只能调用 tool_calling_probe 工具，然后简短确认成功。")
                    .tools(schemaProbeTools)
                    .maxToolCallingRoundTrips(2)
                    .build();
            assistant.chat("请执行工具调用测试");
        } catch (RuntimeException exception) {
            throw new LlmCallException(
                    "LLM_TOOL_CALLING_FAILED",
                    "模型可以连接，但 Tool Calling 测试失败；请确认模型支持工具调用",
                    false,
                    exception);
        }
        if (!probe.called.get()) {
            throw new LlmCallException(
                    "LLM_TOOL_CALLING_UNSUPPORTED",
                    "模型可以连接，但没有执行 Tool Calling；请更换支持工具调用的模型",
                    false,
                    null);
        }
    }

    private interface ProbeAssistant {
        String chat(String message);
    }

    public static final class ToolProbe {
        private final AtomicBoolean called = new AtomicBoolean();

        @Tool(name = "tool_calling_probe", value = "用于验证模型是否能够调用工具")
        public String probe() {
            called.set(true);
            return "tool-calling-ok";
        }
    }
}
