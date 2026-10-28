package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AgentPlannerTests {

    private final AgentPlanner planner = new AgentPlanner(new ObjectMapper());
    private final UUID user = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();
    private final UUID conversation = UUID.randomUUID();

    private static String crossModuleSteps() {
        return """
                [{"intent":"读出最近面试的薄弱点","doneWhen":"得到至少一条薄弱点","tools":["list_interview_recaps"],"effect":"READ"},
                 {"intent":"按薄弱点调整学习计划","doneWhen":"计划安排已更新","tools":["list_learning_plans","update_learning_plan"],"effect":"WRITE"},
                 {"intent":"确认新时间没有冲突","doneWhen":"冲突检查已返回","tools":["check_calendar_conflict"],"effect":"READ"}]
                """;
    }

    @Test void admitsACrossModuleMultiGoalPlan() {
        AgentPlanner.Admission admission = admit(crossModuleSteps(), Optional.empty());
        assertThat(admission.accepted()).isTrue();
        assertThat(admission.steps()).hasSize(3);
        assertThat(admission.steps().get(1).tools()).containsExactly("list_learning_plans", "update_learning_plan");
    }

    @Test void refusesAPlanThatStaysInsideOneModule() {
        AgentPlanner.Admission admission = admit("""
                [{"intent":"读日程","doneWhen":"已读取","tools":["list_calendar_tasks"],"effect":"READ"},
                 {"intent":"建日程","doneWhen":"已创建","tools":["create_calendar_task"],"effect":"WRITE"},
                 {"intent":"再读一次","doneWhen":"已确认","tools":["list_calendar_tasks"],"effect":"READ"}]
                """, Optional.empty());
        assertThat(admission.rejection()).contains("未跨模块");
    }

    @Test void refusesUnknownToolsAndTooFewOrTooManySteps() {
        assertThat(admit("""
                [{"intent":"读","doneWhen":"好","tools":["delete_the_world"],"effect":"READ"},
                 {"intent":"写","doneWhen":"好","tools":["update_learning_plan"],"effect":"WRITE"},
                 {"intent":"看","doneWhen":"好","tools":["list_calendar_tasks"],"effect":"READ"}]
                """, Optional.empty()).rejection()).contains("未登记的工具");
        assertThat(admit("[{\"intent\":\"一步\",\"doneWhen\":\"好\",\"tools\":[\"list_calendar_tasks\"],\"effect\":\"READ\"}]",
                Optional.empty()).rejection()).contains("3 到 6");
        assertThat(admit("not json", Optional.empty()).rejection()).contains("JSON 数组");
        assertThat(admit("""
                [{"intent":"读","doneWhen":"","tools":["list_calendar_tasks"],"effect":"READ"},
                 {"intent":"写","doneWhen":"好","tools":["update_learning_plan"],"effect":"WRITE"},
                 {"intent":"看","doneWhen":"好","tools":["find_available_slots"],"effect":"READ"}]
                """, Optional.empty()).rejection()).contains("完成条件");
    }

    @Test void refusesASecondPlanWhileOneIsRunningAndStopsAfterTheRevisionCap() {
        assertThat(admit(crossModuleSteps(), Optional.of(plan("RUNNING", 0))).rejection()).contains("已有进行中的计划");
        assertThat(admit(crossModuleSteps(), Optional.of(plan("FAILED", 2))).rejection()).contains("重规划次数已达上限");
        assertThat(admit(crossModuleSteps(), Optional.of(plan("FAILED", 1))).accepted()).isTrue();
    }

    @Test void onlyToolsOfOpenStepsStayInScope() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, "VERIFIED", List.of("list_interview_recaps")),
                step(1, "PENDING", List.of("list_learning_plans", "update_learning_plan")),
                step(2, "PENDING", List.of("check_calendar_conflict")));
        assertThat(planner.refusal(running, "update_learning_plan")).isEmpty();
        assertThat(planner.refusal(running, "list_interview_recaps")).get()
                .asString().contains("PLAN_SCOPE_REFUSED");
        // A verified step's tool is out of scope: re-reading is allowed but the plan must move forward.
        assertThat(running.openToolScope()).containsExactly("list_learning_plans", "update_learning_plan",
                "check_calendar_conflict");
    }

    @Test void aVerifiedStepIsNotAttributedAgain() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, "VERIFIED", List.of("list_interview_recaps")),
                step(1, "PENDING", List.of("update_learning_plan")));
        AgentPlanner.StepOutcome outcome = planner.attribute(running, "list_interview_recaps", true, "[{\"title\":\"A 厂\"}]");
        assertThat(outcome).isSameAs(AgentPlanner.StepOutcome.IGNORED);
    }

    @Test void anExecutedCallVerifiesTheEarliestOpenStepThatAskedForIt() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, "PENDING", List.of("list_interview_recaps")),
                step(1, "PENDING", List.of("list_interview_recaps", "update_learning_plan")));
        AgentPlanner.StepOutcome first = planner.attribute(running, "list_interview_recaps", true, "结果");
        assertThat(first.verified()).isTrue();
        assertThat(first.stepIndex()).isZero();
        assertThat(planner.mayRevise(first)).isFalse();
    }

    @Test void aRefusedWriteFailsItsStepAndForbidsOverclaiming() {
        AgentPlanStore.Plan running = plan("RUNNING", 0, step(0, "PENDING", List.of("update_learning_plan")));
        AgentPlanner.StepOutcome outcome = planner.attribute(running, "update_learning_plan", true,
                "{\"status\":\"PERMISSION_NOT_GRANTED\",\"message\":\"用户未确认\"}");
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.guidance()).contains("未能完成").contains("不要改口声称成功");
        assertThat(planner.mayRevise(outcome)).isTrue();
    }

    @Test void aCallThatNeverExecutedFailsEvenWhenTheTextLooksLikeSuccess() {
        AgentPlanStore.Plan running = plan("RUNNING", 0, step(0, "PENDING", List.of("create_calendar_task")));
        assertThat(planner.attribute(running, "create_calendar_task", false, "已成功创建").failed()).isTrue();
        assertThat(planner.attribute(running, "create_calendar_task", true, null).failed()).isTrue();
    }

    private AgentPlanner.Admission admit(String stepsJson, Optional<AgentPlanStore.Plan> existing) {
        return planner.admit(user, request, conversation, "结合面试薄弱点调整学习计划并检查日历冲突", stepsJson, existing);
    }

    private AgentPlanStore.Plan plan(String status, int revision, AgentPlanStore.Step... steps) {
        return new AgentPlanStore.Plan(UUID.randomUUID(), user, request, conversation, "目标", status, revision,
                AgentPlanner.MODEL_CALL_CEILING, 0, java.time.Instant.now(), List.of(steps));
    }

    private AgentPlanStore.Step step(int index, String status, List<String> tools) {
        return new AgentPlanStore.Step(UUID.randomUUID(), index, "步骤 " + index, "完成条件", tools, "READ", status,
                AgentPlanStore.stepActionId(UUID.randomUUID(), index), null);
    }
}
