package com.projectardor.assistant.execution.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.projectardor.platform.model.llm.service.LlmConfigService;

/** Input already includes system/tools/history/references. Only output and margin are reserved again. */
@Component
public final class AgentContextBudgetPolicy {
    private final ModelContextCapacity capacity;
    private final int unknownSoftLimit;
    private final double softRatio;
    private final double safetyRatio;

    public AgentContextBudgetPolicy(ModelContextCapacity capacity,
            @Value("${app.agent.context.max-history-tokens:200000}") int unknownSoftLimit,
            @Value("${app.agent.context.soft-utilization:0.75}") double softRatio,
            @Value("${app.agent.context.safety-reserve-ratio:0.05}") double safetyRatio) {
        if (!Double.isFinite(softRatio) || softRatio <= 0 || softRatio >= 1
                || !Double.isFinite(safetyRatio) || safetyRatio < 0 || safetyRatio >= 1)
            throw new IllegalArgumentException("上下文预算比例配置无效");
        this.capacity = capacity;
        this.unknownSoftLimit = Math.max(1000, unknownSoftLimit);
        this.softRatio = softRatio;
        this.safetyRatio = safetyRatio;
    }

    public Budget resolve(LlmConfigService.LlmRuntimeConfig config, int outputTokens) {
        Long window = capacity.resolve(config);
        long output = Math.max(0, outputTokens);
        if (window == null || window <= 0) return new Budget(null, unknownSoftLimit, null, output, 0);
        long margin = (long) Math.ceil(window * safetyRatio);
        long hard = Math.max(0, window - margin - output);
        long soft = (long) Math.floor(hard * softRatio);
        return new Budget(window, soft, hard, output, margin);
    }

    public record Budget(Long windowTokens, long softInputTokens, Long hardInputTokens,
            long outputReserveTokens, long safetyReserveTokens) {
        public boolean shouldCompact(long inputTokens) { return inputTokens >= softInputTokens; }
        public boolean exceedsHardLimit(long inputTokens) { return hardInputTokens != null && inputTokens > hardInputTokens; }
    }
}
