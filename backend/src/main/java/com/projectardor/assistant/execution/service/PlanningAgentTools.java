package com.projectardor.assistant.execution.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.projectardor.assistant.tools.modules.AgentToolBundle;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * The planning entry point for one run. Proposing a plan records an execution plan; it performs no
 * business work, so it stays a read-level control action and the writes still go through the normal
 * tools with their own authorization.
 */
public final class PlanningAgentTools implements AgentToolBundle {

    private final UUID userId;
    private final UUID requestId;
    private final UUID conversationId;
    private final AgentPlanner planner;
    private final AgentPlanStore store;
    private final AgentRunTracer tracer;

    private volatile AgentPlanStore.Plan plan;
    private volatile UUID planSpanId;

    public PlanningAgentTools(UUID userId, UUID requestId, UUID conversationId,
            AgentPlanner planner, AgentPlanStore store, AgentRunTracer tracer) {
        this.userId = userId;
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.planner = planner;
        this.store = store;
        this.tracer = tracer;
    }

    @Override
    public List<Object> toolObjects() {
        return List.of(this);
    }

    public Optional<AgentPlanStore.Plan> plan() {
        return Optional.ofNullable(plan);
    }

    @Tool(name = "propose_plan", value = """
            仅当本轮任务同时涉及两个以上业务模块、且需要多个目标按顺序完成时调用，例如“结合面试薄弱点调整学习计划并检查日历冲突”。\
            普通问答、单项操作、同一模块内的多步操作都不要调用本工具，直接按正常流程完成。\
            本工具只登记计划，不会修改任何业务数据：每个步骤必须给出可核验的完成条件 doneWhen，\
            tools 只能填写已登记的工具名，effect 按该步骤是否会改数据填 READ 或 WRITE。\
            计划被接受后按步骤顺序继续调用相应工具，不要跳过未核验的步骤，也不要替用户声称完成。""")
    public Map<String, Object> proposePlan(
            @P("本轮任务的总目标，一句话，使用简洁中文") String goal,
            @P("步骤数组的 JSON 字符串，形如 "
                    + "[{\"intent\":\"读出最近面试的薄弱点\",\"doneWhen\":\"已得到至少一条薄弱点结论\",\"tools\":[\"list_interview_recaps\"],\"effect\":\"READ\"}]，3 到 6 步")
                    String stepsJson) {
        Optional<AgentPlanStore.Plan> existing = store.findByRequest(userId, requestId);
        AgentPlanner.Admission admission = planner.admit(userId, requestId, conversationId, goal, stepsJson, existing);
        if (!admission.accepted()) {
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("accepted", false);
            refused.put("reason", admission.rejection());
            refused.put("instruction", "请按普通流程继续，不要再尝试登记计划");
            return refused;
        }
        int revision = existing.map(AgentPlanStore.Plan::revision).orElse(-1) + 1;
        UUID planId = store.create(userId, requestId, conversationId, goal, revision,
                AgentPlanner.MODEL_CALL_CEILING, admission.steps());
        AgentPlanStore.Plan created = store.find(userId, planId).orElseThrow();
        plan = created;
        recordTrace(created);
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("accepted", true);
        accepted.put("planId", planId.toString());
        accepted.put("revision", revision);
        accepted.put("steps", describe(created));
        accepted.put("remainingToolScope", created.openToolScope());
        accepted.put("instruction", "按步骤顺序继续；每一步完成后系统会依据工具执行结果自动核验");
        return accepted;
    }

    private List<Map<String, Object>> describe(AgentPlanStore.Plan created) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (AgentPlanStore.Step step : created.steps()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", step.index() + 1);
            entry.put("intent", step.intent());
            entry.put("doneWhen", step.doneWhen());
            entry.put("tools", step.tools());
            entry.put("effect", step.effect());
            steps.add(entry);
        }
        return steps;
    }

    private void recordTrace(AgentPlanStore.Plan created) {
        if (tracer == null) return;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("planId", created.id().toString());
        attributes.put("revision", created.revision());
        attributes.put("stepCount", created.steps().size());
        attributes.put("goalPreview", TracePreview.of(created.goal()));
        attributes.put("tools", created.steps().stream().flatMap(step -> step.tools().stream()).distinct().toList());
        planSpanId = tracer.startChild("PLAN", "agent.plan", attributes);
    }

    /**
     * Closes the plan span when the run ends. The verified count is what the plan actually achieved
     * according to recorded tool outcomes, so a run that claimed success but left steps open shows it.
     */
    public void finishPlanSpan(String status, AgentPlanStore.Plan finalPlan) {
        if (tracer == null || planSpanId == null) return;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("verifiedSteps", finalPlan.verifiedSteps());
        attributes.put("stepCount", finalPlan.steps().size());
        attributes.put("steps", describe(finalPlan));
        tracer.finishChild(planSpanId, status, null, null, null, null, attributes);
        planSpanId = null;
    }

    /** One span per step, so the graph shows the plan and its verification next to the real tool calls. */
    public void recordStepSpan(int stepIndex, String intent, boolean verified, String evidence) {
        if (tracer == null) return;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("stepIndex", stepIndex);
        attributes.put("intentPreview", TracePreview.of(intent));
        attributes.put("verified", verified);
        attributes.put("evidencePreview", TracePreview.of(evidence == null ? "" : evidence));
        UUID span = tracer.startChild("PLAN_STEP", "plan.step." + (stepIndex + 1), attributes);
        if (span != null) tracer.finishChild(span, verified ? "COMPLETED" : "FAILED",
                verified ? null : "STEP_UNVERIFIED", null, null, null, Map.of("verified", verified));
    }
}
