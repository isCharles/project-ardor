package com.projectardor.assistant.execution.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.projectardor.assistant.tools.capability.ToolCatalog;
import com.projectardor.assistant.tools.capability.ToolModule;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Decides who may plan, what a step may touch, and whether a step is actually satisfied.
 *
 * <p>The gate is deliberately narrow: a plan is admitted only when the steps span at least two
 * business modules and every step names registered tools with a stated completion condition. A
 * single-module request — however many calls it takes — keeps using the ordinary bounded loop, so
 * ordinary chat pays nothing for this layer. Verification is decided from the recorded execution
 * outcome, never from the model's prose about it.
 */
@Service
public class AgentPlanner {

    static final int MIN_STEPS = 3;
    static final int MAX_STEPS = 6;
    static final int MAX_TOOLS_PER_STEP = 4;
    static final int MIN_MODULES = 2;
    static final int REVISION_CEILING = 2;
    static final int MODEL_CALL_CEILING = 12;
    private static final int TEXT_LIMIT = 200;
    /** Result markers that mean the tool did not accomplish anything, mirrored from the wrapper. */
    private static final Set<String> REFUSALS = Set.of(
            "PERMISSION_NOT_GRANTED", "TOOL_NOT_PERMITTED", "DUPLICATE_WRITE_BLOCKED", "PLAN_SCOPE_REFUSED");

    private final ObjectMapper objectMapper;

    public AgentPlanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Admission(List<AgentPlanStore.StepDraft> steps, String rejection) {
        public boolean accepted() {
            return rejection == null;
        }

        static Admission rejected(String reason) {
            return new Admission(List.of(), reason);
        }
    }

    /** What the wrapper should do after one recorded tool outcome for the active plan. */
    public record StepOutcome(UUID stepId, int stepIndex, boolean verified, boolean failed,
            Set<String> toolsDone, String guidance) {
        static final StepOutcome IGNORED = new StepOutcome(null, -1, false, false, Set.of(), null);
    }

    public Admission admit(UUID userId, UUID requestId, UUID conversationId, String goal, String stepsJson,
            Optional<AgentPlanStore.Plan> existing) {
        if (goal == null || goal.isBlank()) return Admission.rejected("总目标不能为空");
        if (existing.isPresent() && existing.get().active()) {
            return Admission.rejected("本轮已有进行中的计划；请按当前计划的剩余步骤继续，或先向用户说明进展");
        }
        if (existing.isPresent() && existing.get().revision() >= REVISION_CEILING) {
            return Admission.rejected("重规划次数已达上限（" + REVISION_CEILING + " 次）；请停止重排，向用户说明未完成的部分和原因");
        }
        List<AgentPlanStore.StepDraft> drafts;
        try {
            drafts = parseSteps(stepsJson);
        } catch (IllegalArgumentException failure) {
            return Admission.rejected(failure.getMessage());
        }
        if (drafts.size() < MIN_STEPS || drafts.size() > MAX_STEPS) {
            return Admission.rejected("计划步骤数必须在 " + MIN_STEPS + " 到 " + MAX_STEPS + " 之间；不足时请直接执行");
        }
        EnumSet<ToolModule> modules = EnumSet.noneOf(ToolModule.class);
        for (AgentPlanStore.StepDraft draft : drafts) {
            for (String tool : draft.tools()) {
                ToolCatalog.Risk risk = ToolCatalog.describe(tool);
                if (risk == null) return Admission.rejected("未登记的工具：" + tool + "；计划只能使用已登记工具");
                modules.add(risk.module());
            }
        }
        if (modules.size() < MIN_MODULES) {
            return Admission.rejected("任务未跨模块，无需计划；按普通流程直接完成");
        }
        return new Admission(drafts, null);
    }

    private List<AgentPlanStore.StepDraft> parseSteps(String stepsJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stepsJson == null ? "" : stepsJson);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("步骤必须是 JSON 数组：[{\"intent\":\"…\",\"doneWhen\":\"…\",\"tools\":[\"…\"],\"effect\":\"READ|WRITE\"}]");
        }
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("步骤必须是 JSON 数组，且至少一步");
        }
        List<AgentPlanStore.StepDraft> drafts = new ArrayList<>();
        for (JsonNode step : root) {
            String intent = text(step, "intent");
            String doneWhen = text(step, "doneWhen");
            String effect = text(step, "effect");
            List<String> tools = new ArrayList<>();
            JsonNode toolNodes = step.path("tools");
            if (toolNodes.isArray()) toolNodes.forEach(node -> tools.add(node.asText()));
            if (intent.isBlank() || intent.length() > TEXT_LIMIT) throw new IllegalArgumentException("每一步都要写清楚 intent（不超过 " + TEXT_LIMIT + " 字）");
            if (doneWhen.isBlank() || doneWhen.length() > TEXT_LIMIT) throw new IllegalArgumentException("每一步都要给出完成条件 doneWhen");
            if (tools.isEmpty() || tools.size() > MAX_TOOLS_PER_STEP) throw new IllegalArgumentException("每一步需要 1 到 " + MAX_TOOLS_PER_STEP + " 个已登记工具");
            if (!"READ".equals(effect) && !"WRITE".equals(effect)) throw new IllegalArgumentException("effect 只能是 READ 或 WRITE");
            drafts.add(new AgentPlanStore.StepDraft(intent, doneWhen, List.copyOf(tools), effect));
        }
        return List.copyOf(drafts);
    }

    /** A tool the model wants to call now, judged against the steps that are still open. */
    public Optional<String> refusal(AgentPlanStore.Plan plan, String tool) {
        if (plan == null) return Optional.empty();
        if (plan.openToolScope().contains(tool)) return Optional.empty();
        return Optional.of("{\"status\":\"PLAN_SCOPE_REFUSED\",\"retryable\":false,"
                + "\"message\":\"该工具不在当前计划未完成步骤的范围内；先完成当前步骤，或向用户说明需要调整计划，不要改做别的写入。\"}");
    }

    /**
     * Attributes one recorded tool outcome to the earliest open step that asked for that tool.
     * A step is verified only once every tool it declared has run successfully, so a read cannot
     * stand in for the write it was paired with; a refused or unexecuted call fails the step.
     *
     * @param toolsDoneThisRun the tools this run already completed for that step, without this call
     */
    public StepOutcome attribute(AgentPlanStore.Plan plan, String tool, boolean executed, String result,
            Set<String> toolsDoneThisRun) {
        if (plan == null) return StepOutcome.IGNORED;
        Optional<AgentPlanStore.Step> match = plan.steps().stream()
                .filter(step -> step.open() && step.tools().contains(tool))
                .findFirst();
        if (match.isEmpty()) return StepOutcome.IGNORED;
        AgentPlanStore.Step step = match.get();
        boolean refused = executed && result != null && REFUSALS.stream().anyMatch(result::contains);
        if (!executed || refused || result == null) {
            return new StepOutcome(step.id(), step.index(), false, true, toolsDoneThisRun,
                    "步骤 " + (step.index() + 1) + "「" + step.intent() + "」未能完成："
                            + (refused ? "本次写入被拒绝" : "工具调用未执行或未返回结果")
                            + "。请如实说明未完成，不要改口声称成功。");
        }
        Set<String> done = new java.util.LinkedHashSet<>(toolsDoneThisRun);
        done.add(tool);
        List<String> missing = step.tools().stream().filter(name -> !done.contains(name)).toList();
        if (!missing.isEmpty()) {
            return new StepOutcome(step.id(), step.index(), false, false, done,
                    "步骤 " + (step.index() + 1) + "「" + step.intent() + "」已记录 " + tool
                            + "，还差：" + String.join("、", missing) + "。继续完成这些动作后该步骤才算核验通过。");
        }
        return new StepOutcome(step.id(), step.index(), true, false, done,
                "步骤 " + (step.index() + 1) + "「" + step.intent() + "」已按工具执行结果核验通过，请继续下一步。");
    }

    /** The three situations that legitimately justify a new plan; anything else must not re-plan. */
    public boolean mayRevise(StepOutcome outcome) {
        return outcome != null && outcome.failed();
    }

    public String planSummary(AgentPlanStore.Plan plan) {
        StringBuilder summary = new StringBuilder("计划（共 ").append(plan.steps().size()).append(" 步，已核验 ")
                .append(plan.verifiedSteps()).append("）：");
        for (AgentPlanStore.Step step : plan.steps()) {
            summary.append(' ').append(step.index() + 1).append('.').append(step.intent())
                    .append('[').append(step.status()).append("] 完成条件：").append(step.doneWhen()).append(';');
        }
        return summary.toString();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").strip();
    }
}
