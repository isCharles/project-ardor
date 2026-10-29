package com.projectardor.assistant.execution.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    /** Actual results this run recorded per step; a step with several tools is not done after one. */
    private final Map<String, Map<String, String>> stepResults = new ConcurrentHashMap<>();

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

    /**
     * A write the still-open steps never asked for is refused here, before it can reach a business
     * service: the plan limits what this run may do instead of merely describing it.
     */
    Optional<String> refusal(String tool) {
        AgentPlanStore.Plan current = plan;
        if (current == null || !current.active()) return Optional.empty();
        return planner.refusal(current, tool);
    }

    /**
     * Settles one recorded tool outcome against the plan. Verification comes from the execution
     * fact — the call ran, or it was refused — never from the model's prose about it, and the tool
     * result handed back to the model is left exactly as the business service returned it.
     */
    void applyOutcome(String tool, boolean executed, String result) {
        AgentPlanStore.Plan current = plan;
        if (current == null || !current.active()) return;
        AgentPlanStore.Step step = current.currentStep()
                .filter(candidate -> !candidate.failed() && candidate.tools().contains(tool))
                .orElse(null);
        AgentPlanner.StepOutcome outcome = planner.attribute(current, tool, executed, result,
                step == null ? Map.of() : stepResults.getOrDefault(step.id().toString(), Map.of()));
        if (outcome.stepId() == null) return;
        if (outcome.verified()) {
            store.verifyStep(userId, outcome.stepId(), outcome.evidence());
            stepResults.remove(outcome.stepId().toString());
        } else if (outcome.failed()) {
            store.failStep(userId, outcome.stepId(),
                    outcome.evidence() != null ? outcome.evidence() : outcome.guidance());
            stepResults.remove(outcome.stepId().toString());
        } else {
            // The step asked for more than this one call. It stays open; only this run knows how far it got.
            store.startStep(userId, outcome.stepId());
            stepResults.put(outcome.stepId().toString(), outcome.results());
        }
        if (outcome.verified() || outcome.failed()) {
            recordStepSpan(outcome.stepIndex(), step == null ? tool : step.intent(), outcome.verified(),
                    outcome.evidence() != null ? outcome.evidence() : outcome.guidance());
        }
        // The next call is judged against the current step, not the step just settled.
        plan = store.find(userId, current.id()).orElse(current);
    }

    /**
     * Closes the plan with the run. Steps the run never reached stay recorded as skipped, so a trace
     * reading shows "verified 2 of 4" rather than implying the goal was met.
     */
    void finish(String runStatus) {
        AgentPlanStore.Plan held = plan;
        if (held == null) return;
        // The durable step states are what this run achieved, not whatever this object last saw.
        AgentPlanStore.Plan current = store.find(userId, held.id()).orElse(held);
        store.skipOpenSteps(userId, current.id());
        boolean achieved = "COMPLETED".equals(runStatus) && current.verifiedSteps() == current.steps().size();
        String planStatus = achieved ? "COMPLETED"
                : "CANCELLED".equals(runStatus) ? "CANCELLED" : "FAILED";
        store.finish(userId, current.id(), planStatus);
        AgentPlanStore.Plan finalPlan = store.find(userId, current.id()).orElse(current);
        plan = finalPlan;
        finishPlanSpan(achieved ? "COMPLETED" : "FAILED", finalPlan);
    }

    @Tool(name = "propose_plan", value = """
            仅当本轮任务同时涉及两个以上业务模块、且需要多个目标按顺序完成时调用，例如“结合面试薄弱点调整学习计划并检查日历冲突”。\
            普通问答、单项操作、同一模块内的多步操作都不要调用本工具，直接按正常流程完成。\
            本工具只登记计划，不会修改任何业务数据。每个步骤必须给出：\
            intent、给人看的 doneWhen、以及可机器核验的 verify；verify.tool 必须是本步骤 tools 之一，\
            并按该次调用的真实返回选择条件：MIN_ITEMS/MAX_ITEMS（数组条数）、AT_LEAST（数字）、\
            EQUALS/CONTAINS（值）、HAS_ID（返回记录 ID）、SUCCESS（成功状态或带 ID 的资源快照）。\
            外部网页结果用 path $.results；知识检索返回数组用 $；外部文本用 CONTAINS。\
            tools 只能填写已登记的工具名，effect 按该步骤是否会改数据填 READ 或 WRITE。\
            计划被接受后严格按当前步骤执行，后续步骤的写入会被拒绝；当前步骤失败后才能重新调用本工具提交修订计划。\
            系统只依据 verify 检查真实工具结果，不要用 doneWhen 或对话文本代替执行，也不要替用户声称完成。""")
    public Map<String, Object> proposePlan(
            @P("本轮任务的总目标，一句话，使用简洁中文") String goal,
            @P("步骤数组的 JSON 字符串，形如 "
                    + "[{\"intent\":\"读出最近面试的薄弱点\",\"doneWhen\":\"已得到至少一条薄弱点结论\","
                    + "\"verify\":{\"tool\":\"list_interview_recaps\",\"path\":\"$\",\"op\":\"MIN_ITEMS\",\"value\":1},"
                    + "\"tools\":[\"list_interview_recaps\"],\"effect\":\"READ\"}]，3 到 6 步")
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
        boolean replacing = existing.filter(AgentPlanStore.Plan::active).isPresent();
        UUID planId;
        if (replacing) {
            AgentPlanStore.Plan superseded = existing.orElseThrow();
            // Close the previous span before starting the revised plan; its verified steps stay as facts.
            finishPlanSpan("FAILED", superseded);
            stepResults.clear();
            planId = store.createRevision(userId, requestId, conversationId, goal, revision,
                    AgentPlanner.MODEL_CALL_CEILING, admission.steps(), superseded.id());
        } else {
            planId = store.create(userId, requestId, conversationId, goal, revision,
                    AgentPlanner.MODEL_CALL_CEILING, admission.steps());
        }
        store.markRunning(userId, planId);
        AgentPlanStore.Plan created = store.find(userId, planId).orElseThrow();
        plan = created;
        recordTrace(created);
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("accepted", true);
        accepted.put("planId", planId.toString());
        accepted.put("revision", revision);
        accepted.put("revised", replacing);
        accepted.put("steps", describe(created));
        accepted.put("currentToolScope", created.currentToolScope());
        accepted.put("instruction", "严格按当前步骤执行；每一步完成后系统会依据 verify 和真实工具结果自动核验");
        return accepted;
    }

    private List<Map<String, Object>> describe(AgentPlanStore.Plan created) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (AgentPlanStore.Step step : created.steps()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", step.index() + 1);
            entry.put("intent", step.intent());
            entry.put("doneWhen", step.doneWhen());
            AgentPlanStore.Verification verification = step.verification();
            entry.put("verify", verification == null ? null : Map.of(
                    "tool", verification.tool(), "path", verification.path(), "op", verification.op(),
                    "value", verification.value() == null ? "" : verification.value()));
            entry.put("tools", step.tools());
            entry.put("effect", step.effect());
            entry.put("status", step.status());
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
