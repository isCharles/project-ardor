package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plan the model proposed has to bind the run that accepted it: only the current step may
 * write, a write outside it is refused before anyone is asked to approve it, and each step is
 * settled by a verifier over the real tool result.
 */
class ApprovedAgentToolsPlanFenceTests {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID READ_STEP = UUID.randomUUID();
    private static final UUID LEARNING_STEP = UUID.randomUUID();
    private static final UUID CALENDAR_STEP = UUID.randomUUID();
    private static final UUID AUDIT_ROW = UUID.randomUUID();

    private static final String GOAL = "结合面试薄弱点调整学习计划并排入日历";
    private static final String STEPS = """
            [{"intent":"读出最近面试的薄弱点","doneWhen":"已得到至少一条薄弱点结论","verify":{"tool":"list_interview_recaps","path":"$","op":"MIN_ITEMS","value":1},"tools":["list_interview_recaps"],"effect":"READ"},
             {"intent":"按薄弱点调整学习安排","doneWhen":"学习计划已更新","verify":{"tool":"update_learning_plan","path":"$","op":"SUCCESS"},"tools":["update_learning_plan"],"effect":"WRITE"},
             {"intent":"把复习安排进日历","doneWhen":"日程已创建","verify":{"tool":"create_calendar_task","path":"$","op":"SUCCESS"},"tools":["create_calendar_task"],"effect":"WRITE"}]
            """;

    static class ProbeTools {
        final AtomicInteger recaps = new AtomicInteger();
        final AtomicInteger learning = new AtomicInteger();
        final AtomicInteger calendar = new AtomicInteger();
        final AtomicInteger offPlan = new AtomicInteger();

        @Tool(name = "list_interview_recaps", value = "列出面经")
        public List<Map<String, Object>> listInterviewRecaps() {
            recaps.incrementAndGet();
            return List.of(Map.of("recapId", "r1", "weakQuestionCount", 1));
        }

        @Tool(name = "update_learning_plan", value = "调整学习计划")
        public String updateLearningPlan(@P("说明") String note) {
            learning.incrementAndGet();
            return "{\"status\":\"OK\"}";
        }

        @Tool(name = "create_calendar_task", value = "创建日程")
        public String createCalendarTask(@P("标题") String title) {
            calendar.incrementAndGet();
            // A write that reports it blocked itself must not be able to verify its step.
            return "{\"status\":\"DUPLICATE_WRITE_BLOCKED\"}";
        }

        @Tool(name = "update_memory", value = "改写记忆")
        public String updateMemory(@P("内容") String content) {
            offPlan.incrementAndGet();
            return "must never run";
        }
    }

    private static AgentPlanStore.Step step(UUID id, int index, String status, String tool, String effect) {
        String op = "READ".equals(effect) ? "MIN_ITEMS" : "SUCCESS";
        var verification = new AgentPlanStore.Verification(tool, "$", op, "READ".equals(effect) ? "1" : null);
        return new AgentPlanStore.Step(id, index, "步骤 " + index, "完成条件", verification, List.of(tool), effect,
                status, AgentPlanStore.stepActionId(UUID.randomUUID(), index), null);
    }

    private static List<AgentPlanStore.Step> steps(String... statuses) {
        return List.of(
                step(READ_STEP, 0, statuses[0], "list_interview_recaps", "READ"),
                step(LEARNING_STEP, 1, statuses[1], "update_learning_plan", "WRITE"),
                step(CALENDAR_STEP, 2, statuses[2], "create_calendar_task", "WRITE"));
    }

    private static AgentPlanStore.Plan plan(List<AgentPlanStore.Step> steps) {
        return new AgentPlanStore.Plan(PLAN, USER, RUN, CONVERSATION, GOAL, "RUNNING", 0,
                AgentPlanner.MODEL_CALL_CEILING, 0, Instant.now(), steps);
    }

    /** Registers the plan through the real admission gate; only persistence is mocked. */
    private static PlanningAgentTools proposedPlan(AgentPlanStore store, AgentRunTracer tracer,
            List<AgentPlanStore.Step> persisted) {
        when(store.findByRequest(USER, RUN)).thenReturn(Optional.empty());
        when(store.create(eq(USER), eq(RUN), eq(CONVERSATION), anyString(), anyInt(), anyInt(), anyList())).thenReturn(PLAN);
        when(store.find(USER, PLAN)).thenReturn(Optional.of(plan(persisted)));
        when(store.markRunning(USER, PLAN)).thenReturn(1);
        var planning = new PlanningAgentTools(USER, RUN, CONVERSATION,
                new AgentPlanner(JsonMapper.builder().build()), store, tracer);
        assertThat(planning.proposePlan(GOAL, STEPS)).containsEntry("accepted", true);
        return planning;
    }

    private static Map<String, ToolExecutor> executors(ProbeTools tools, PlanningAgentTools planning,
            AgentExecutionAuditStore audit, AgentRunTracer tracer, AgentPermissionGrants permissions) {
        var approvals = mock(AgentToolApprovals.class);
        when(approvals.canPropose(eq(USER), any())).thenReturn(true);
        when(approvals.canRead(eq(USER), any())).thenReturn(true);
        Map<String, ToolExecutor> byName = new LinkedHashMap<>();
        ApprovedAgentTools.wrap(tools, USER, RUN, approvals, null, new AgentToolTimings(), waiting -> {},
                        GOAL, permissions, audit, CONVERSATION, false, tracer, planning)
                .forEach((specification, executor) -> byName.put(specification.name(), executor));
        return byName;
    }

    private static AgentPermissionGrants autoApproving() {
        var permissions = mock(AgentPermissionGrants.class);
        when(permissions.autoApproves(eq(USER), anyString())).thenReturn(true);
        when(permissions.requiresConfirmationForBatch(eq(USER), anyInt())).thenReturn(false);
        return permissions;
    }

    private static ToolExecutionRequest request(String tool, String arguments) {
        return ToolExecutionRequest.builder().id("call-" + tool).name(tool).arguments(arguments).build();
    }

    @Test
    void inScopeWriteVerifiesItsStepWhileAnOffPlanWriteIsRefusedAndRecorded() {
        var store = mock(AgentPlanStore.class);
        var audit = mock(AgentExecutionAuditStore.class);
        when(audit.startToolCall(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(AUDIT_ROW);
        var tracer = mock(AgentRunTracer.class);
        UUID planSpan = UUID.randomUUID(), learningSpan = UUID.randomUUID(), blockedSpan = UUID.randomUUID();
        when(tracer.startChild(eq("TOOL"), eq("update_learning_plan"), any())).thenReturn(learningSpan);
        when(tracer.startChild(eq("TOOL"), eq("update_memory"), any())).thenReturn(blockedSpan);
        UUID stepSpan = UUID.randomUUID();
        when(tracer.startChild(eq("PLAN_STEP"), eq("plan.step.2"), any())).thenReturn(stepSpan);
        var permissions = autoApproving();
        var planning = proposedPlan(store, tracer, steps(AgentPlanStore.VERIFIED, AgentPlanStore.PENDING,
                AgentPlanStore.PENDING));
        var tools = new ProbeTools();
        var byName = executors(tools, planning, audit, tracer, permissions);
        assertThat(byName).containsKeys("propose_plan", "update_learning_plan", "update_memory");

        assertThat(byName.get("update_learning_plan").execute(request("update_learning_plan", "{\"note\":\"weakness\"}"), null))
                .contains("\"status\":\"OK\"");
        assertThat(tools.learning).hasValue(1);
        verify(store).verifyStep(eq(USER), eq(LEARNING_STEP),
                argThat((String evidence) -> evidence.startsWith("verify=SUCCESS") && !evidence.contains("完成条件")));
        verify(tracer).finishChild(eq(stepSpan), eq("COMPLETED"), isNull(), isNull(), isNull(), isNull(),
                argThat((Map<String, Object> attributes) -> Boolean.TRUE.equals(attributes.get("verified"))));

        String refused = byName.get("update_memory").execute(request("update_memory", "{\"content\":\"别的\"}"), null);
        assertThat(refused).contains("PLAN_SCOPE_REFUSED");
        assertThat(tools.offPlan).as("a write no open step asked for never reaches the business service").hasValue(0);
        verify(audit).finishToolCall(AUDIT_ROW, USER, "NOT_EXECUTED", null, "PLAN_SCOPE_REFUSED");
        verify(tracer).finishChild(eq(blockedSpan), eq("NOT_EXECUTED"), eq("PLAN_SCOPE_REFUSED"),
                isNull(), isNull(), isNull(), any());
        verify(permissions, never()).recordAutoApproval(eq(USER), eq(RUN), eq("update_memory"), anyString());
    }

    @Test
    void aLaterStepWriteCannotRunBeforeTheCurrentStepIsVerified() {
        var store = mock(AgentPlanStore.class);
        var tracer = mock(AgentRunTracer.class);
        when(tracer.startChild(eq("TOOL"), eq("update_learning_plan"), any())).thenReturn(UUID.randomUUID());
        var planning = proposedPlan(store, tracer, steps(AgentPlanStore.PENDING, AgentPlanStore.PENDING,
                AgentPlanStore.PENDING));
        var tools = new ProbeTools();

        String refused = executors(tools, planning, null, tracer, autoApproving())
                .get("update_learning_plan").execute(request("update_learning_plan", "{\"note\":\"weakness\"}"), null);

        assertThat(refused).contains("PLAN_SCOPE_REFUSED").contains("后续步骤");
        assertThat(tools.learning).as("the reschedule write never runs before the conflict/current step")
                .hasValue(0);
        verify(store, never()).verifyStep(eq(USER), eq(LEARNING_STEP), anyString());
    }

    @Test
    void aCallThatBlockedItselfFailsItsStepInsteadOfVerifyingIt() {
        var store = mock(AgentPlanStore.class);
        var tracer = mock(AgentRunTracer.class);
        UUID stepSpan = UUID.randomUUID();
        when(tracer.startChild(eq("TOOL"), anyString(), any())).thenReturn(UUID.randomUUID());
        when(tracer.startChild(eq("PLAN_STEP"), eq("plan.step.3"), any())).thenReturn(stepSpan);
        var planning = proposedPlan(store, tracer, steps(AgentPlanStore.VERIFIED, AgentPlanStore.VERIFIED,
                AgentPlanStore.PENDING));
        var byName = executors(new ProbeTools(), planning, null, tracer, autoApproving());

        assertThat(byName.get("create_calendar_task").execute(request("create_calendar_task", "{\"title\":\"复习\"}"), null))
                .contains("DUPLICATE_WRITE_BLOCKED");
        verify(store).failStep(eq(USER), eq(CALENDAR_STEP), anyString());
        verify(store, never()).verifyStep(eq(USER), eq(CALENDAR_STEP), anyString());
        verify(tracer).finishChild(eq(stepSpan), eq("FAILED"), eq("STEP_UNVERIFIED"), isNull(), isNull(), isNull(),
                argThat((Map<String, Object> attributes) -> Boolean.FALSE.equals(attributes.get("verified"))));
    }

    @Test
    void finishingTheRunKeepsUnreachedStepsVisibleAndClosesThePlanSpan() {
        var store = mock(AgentPlanStore.class);
        var tracer = mock(AgentRunTracer.class);
        UUID planSpan = UUID.randomUUID();
        when(tracer.startChild(eq("PLAN"), anyString(), any())).thenReturn(planSpan);
        var planning = proposedPlan(store, tracer, steps(AgentPlanStore.PENDING, AgentPlanStore.PENDING,
                AgentPlanStore.PENDING));

        planning.finish("COMPLETED");

        verify(store).skipOpenSteps(USER, PLAN);
        verify(store).finish(USER, PLAN, "FAILED");
        verify(store, never()).finish(USER, PLAN, "COMPLETED");
        verify(tracer).finishChild(eq(planSpan), eq("FAILED"), isNull(), isNull(), isNull(), isNull(),
                argThat((Map<String, Object> attributes) -> Integer.valueOf(0).equals(attributes.get("verifiedSteps"))
                        && Integer.valueOf(3).equals(attributes.get("stepCount"))));
    }

    @Test
    void aRunThatVerifiedEveryStepCompletesThePlan() {
        var store = mock(AgentPlanStore.class);
        var tracer = mock(AgentRunTracer.class);
        UUID planSpan = UUID.randomUUID();
        when(tracer.startChild(eq("PLAN"), anyString(), any())).thenReturn(planSpan);
        var planning = proposedPlan(store, tracer, steps(AgentPlanStore.VERIFIED, AgentPlanStore.VERIFIED,
                AgentPlanStore.VERIFIED));

        planning.finish("COMPLETED");

        verify(store).finish(USER, PLAN, "COMPLETED");
        verify(tracer).finishChild(eq(planSpan), eq("COMPLETED"), isNull(), isNull(), isNull(), isNull(),
                argThat((Map<String, Object> attributes) -> Integer.valueOf(3).equals(attributes.get("verifiedSteps"))));
    }

    @Test
    void aFailedPlanCanBeRevisedInTheSameRun() {
        var store = mock(AgentPlanStore.class);
        UUID revised = UUID.randomUUID();
        var failed = new AgentPlanStore.Plan(PLAN, USER, RUN, CONVERSATION, GOAL, "RUNNING", 0,
                AgentPlanner.MODEL_CALL_CEILING, 0, Instant.now(),
                steps(AgentPlanStore.FAILED, AgentPlanStore.PENDING, AgentPlanStore.PENDING));
        var replacement = new AgentPlanStore.Plan(revised, USER, RUN, CONVERSATION, GOAL, "RUNNING", 1,
                AgentPlanner.MODEL_CALL_CEILING, 0, Instant.now(),
                steps(AgentPlanStore.PENDING, AgentPlanStore.PENDING, AgentPlanStore.PENDING));
        when(store.findByRequest(USER, RUN)).thenReturn(Optional.of(failed));
        when(store.createRevision(eq(USER), eq(RUN), eq(CONVERSATION), anyString(), eq(1), anyInt(), anyList(),
                eq(PLAN))).thenReturn(revised);
        when(store.find(USER, revised)).thenReturn(Optional.of(replacement));
        when(store.markRunning(USER, revised)).thenReturn(1);
        var planning = new PlanningAgentTools(USER, RUN, CONVERSATION,
                new AgentPlanner(JsonMapper.builder().build()), store, null);

        Map<String, Object> accepted = planning.proposePlan(GOAL, STEPS);

        assertThat(accepted).containsEntry("accepted", true).containsEntry("revision", 1)
                .containsEntry("revised", true);
        verify(store).createRevision(eq(USER), eq(RUN), eq(CONVERSATION), eq(GOAL), eq(1), anyInt(), anyList(),
                eq(PLAN));
        verify(store, never()).create(any(), any(), any(), anyString(), anyInt(), anyInt(), anyList());
        assertThat(planning.plan()).map(AgentPlanStore.Plan::id).contains(revised);
    }

    @Test
    void noPlanMeansNoRefusalsAndNoStepRecords() {
        var store = mock(AgentPlanStore.class);
        var tracer = mock(AgentRunTracer.class);
        when(tracer.startChild(anyString(), anyString(), any())).thenReturn(UUID.randomUUID());
        var planning = new PlanningAgentTools(USER, RUN, CONVERSATION,
                new AgentPlanner(JsonMapper.builder().build()), store, tracer);
        var tools = new ProbeTools();

        assertThat(executors(tools, planning, null, tracer, autoApproving())
                .get("update_memory").execute(request("update_memory", "{\"content\":\"普通请求\"}"), null))
                .contains("must never run");
        assertThat(tools.offPlan).hasValue(1);
        verify(store, never()).verifyStep(eq(USER), any(), anyString());
        verify(tracer, never()).startChild(eq("PLAN_STEP"), anyString(), any());
    }
}
