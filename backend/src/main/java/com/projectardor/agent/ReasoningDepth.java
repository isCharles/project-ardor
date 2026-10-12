package com.projectardor.agent;

public enum ReasoningDepth {
    AUTO, QUICK, STANDARD, DEEP;

    public static ReasoningDepth orAuto(ReasoningDepth depth) { return depth == null ? AUTO : depth; }

    public String guidance() {
        return switch (this) {
            case AUTO -> "";
            case QUICK -> "\n本轮优先快速回答，使用满足任务的最少步骤；必要的授权和事实核验仍须执行。";
            case STANDARD -> "\n本轮平衡响应速度和分析质量，按任务复杂度选择必要的分析与工具。";
            case DEEP -> "\n本轮深入分析关键假设、取舍与证据，必要时使用工具核实，再给出清晰、有依据的结论。";
        };
    }
}
