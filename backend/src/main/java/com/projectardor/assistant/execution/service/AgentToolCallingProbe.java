package com.projectardor.assistant.execution.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.tools.CareerAgentTools;
import com.projectardor.assistant.tools.modules.AgentToolBundle;
import com.projectardor.platform.model.llm.service.LlmCallException;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import com.projectardor.platform.model.llm.service.ToolCallingProbe;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import com.projectardor.assistant.execution.service.LangChainModelFactory;

@Service
public class AgentToolCallingProbe implements ToolCallingProbe {

    private final LangChainModelFactory modelFactory;
    private final CareerAgentTools careerAgentTools;

    public AgentToolCallingProbe(LangChainModelFactory modelFactory, CareerAgentTools careerAgentTools) {
        this.modelFactory = modelFactory;
        this.careerAgentTools = careerAgentTools;
    }

    @Override
    public void verify(LlmConfigService.LlmRuntimeConfig config, UUID userId) {
        ToolProbe probe = new ToolProbe();
        try {
            Map<ToolSpecification, ToolExecutor> schemaProbeTools = new LinkedHashMap<>();
            ToolExecutor noOpExecutor = (request, memoryId) -> "compatibility-schema-ok";
            for (Object target : AgentToolBundle.objects(careerAgentTools.bind(userId, "连接测试"))) {
                ToolSpecifications.toolSpecificationsFrom(target)
                        .forEach(specification -> schemaProbeTools.put(specification, noOpExecutor));
            }
            ToolSpecifications.toolSpecificationsFrom(probe).forEach(specification ->
                    schemaProbeTools.put(specification, (request, memoryId) -> {
                        probe.called.set(true);
                        return "tool-calling-ok";
                    }));
            ProbeAssistant assistant = AiServices.builder(ProbeAssistant.class)
                    .chatModel(modelFactory.create(userId, config, ReasoningDepth.AUTO, List.of()))
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
