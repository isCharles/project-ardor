package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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
                [{"intent":"读出最近面试的薄弱点","doneWhen":"得到至少一条薄弱点","verify":{"tool":"list_interview_recaps","path":"$","op":"MIN_ITEMS","value":1},"tools":["list_interview_recaps"],"effect":"READ"},
                 {"intent":"按薄弱点调整学习计划","doneWhen":"计划安排已更新","verify":{"tool":"update_learning_plan","path":"$","op":"SUCCESS"},"tools":["list_learning_plans","update_learning_plan"],"effect":"WRITE"},
                 {"intent":"确认新时间没有冲突","doneWhen":"冲突检查返回空列表","verify":{"tool":"check_calendar_conflict","path":"$","op":"MAX_ITEMS","value":0},"tools":["check_calendar_conflict"],"effect":"READ"}]
                """;
    }

    @Test void admitsACrossModuleMultiGoalPlan() {
        AgentPlanner.Admission admission = admit(crossModuleSteps(), Optional.empty());
        assertThat(admission.accepted()).isTrue();
        assertThat(admission.steps()).hasSize(3);
        assertThat(admission.steps().get(1).tools()).containsExactly("list_learning_plans", "update_learning_plan");
        assertThat(admission.steps().get(1).verification().op()).isEqualTo("SUCCESS");
    }

    @Test void refusesAPlanThatStaysInsideOneModule() {
        AgentPlanner.Admission admission = admit("""
                [{"intent":"读日程","doneWhen":"已读取","verify":{"tool":"list_calendar_tasks","path":"$","op":"MIN_ITEMS","value":1},"tools":["list_calendar_tasks"],"effect":"READ"},
                 {"intent":"建日程","doneWhen":"已创建","verify":{"tool":"create_calendar_task","path":"$","op":"HAS_ID"},"tools":["create_calendar_task"],"effect":"WRITE"},
                 {"intent":"再读一次","doneWhen":"已确认","verify":{"tool":"list_calendar_tasks","path":"$","op":"MIN_ITEMS","value":1},"tools":["list_calendar_tasks"],"effect":"READ"}]
                """, Optional.empty());
        assertThat(admission.rejection()).contains("未跨模块");
    }

    @Test void refusesUnknownToolsAndTooFewOrTooManySteps() {
        assertThat(admit("""
                [{"intent":"读","doneWhen":"好","verify":{"tool":"delete_the_world","path":"$","op":"SUCCESS"},"tools":["delete_the_world"],"effect":"READ"},
                 {"intent":"写","doneWhen":"好","verify":{"tool":"update_learning_plan","path":"$","op":"SUCCESS"},"tools":["update_learning_plan"],"effect":"WRITE"},
                 {"intent":"看","doneWhen":"好","verify":{"tool":"list_calendar_tasks","path":"$","op":"MIN_ITEMS","value":1},"tools":["list_calendar_tasks"],"effect":"READ"}]
                """, Optional.empty()).rejection()).contains("未登记的工具");
        assertThat(admit("[{\"intent\":\"一步\",\"doneWhen\":\"好\",\"verify\":{\"tool\":\"list_calendar_tasks\",\"op\":\"MIN_ITEMS\",\"value\":1},\"tools\":[\"list_calendar_tasks\"],\"effect\":\"READ\"}]",
                Optional.empty()).rejection()).contains("3 到 6");
        assertThat(admit("not json", Optional.empty()).rejection()).contains("JSON 数组");
        assertThat(admit("""
                [{"intent":"读","doneWhen":"","verify":{"tool":"list_calendar_tasks","path":"$","op":"MIN_ITEMS","value":1},"tools":["list_calendar_tasks"],"effect":"READ"},
                 {"intent":"写","doneWhen":"好","verify":{"tool":"update_learning_plan","path":"$","op":"SUCCESS"},"tools":["update_learning_plan"],"effect":"WRITE"},
                 {"intent":"看","doneWhen":"好","verify":{"tool":"find_available_slots","path":"$","op":"MIN_ITEMS","value":1},"tools":["find_available_slots"],"effect":"READ"}]
                """, Optional.empty()).rejection()).contains("完成条件");
    }

    @Test void refusesAStepWithoutAMachineCheckablePredicate() {
        assertThat(admit("""
                [{"intent":"读","doneWhen":"好","tools":["list_interview_recaps"],"effect":"READ"},
                 {"intent":"写","doneWhen":"好","verify":{"tool":"update_learning_plan","path":"$","op":"SUCCESS"},"tools":["update_learning_plan"],"effect":"WRITE"},
                 {"intent":"看","doneWhen":"好","verify":{"tool":"check_calendar_conflict","path":"$","op":"MAX_ITEMS","value":0},"tools":["check_calendar_conflict"],"effect":"READ"}]
                """, Optional.empty()).rejection()).contains("verify");
        assertThat(admit("""
                [{"intent":"读","doneWhen":"好","verify":{"tool":"get_interview_recap","path":"$","op":"HAS_ID"},"tools":["list_interview_recaps"],"effect":"READ"},
                 {"intent":"写","doneWhen":"好","verify":{"tool":"update_learning_plan","path":"$","op":"SUCCESS"},"tools":["update_learning_plan"],"effect":"WRITE"},
                 {"intent":"看","doneWhen":"好","verify":{"tool":"check_calendar_conflict","path":"$","op":"MAX_ITEMS","value":0},"tools":["check_calendar_conflict"],"effect":"READ"}]
                """, Optional.empty()).rejection()).contains("必须是本步骤 tools");
        assertThat(admit("""
                [{"intent":"读","doneWhen":"好","verify":{"tool":"list_interview_recaps","path":"$","op":"TRUTHY"},"tools":["list_interview_recaps"],"effect":"READ"},
                 {"intent":"写","doneWhen":"好","verify":{"tool":"update_learning_plan","path":"$","op":"SUCCESS"},"tools":["update_learning_plan"],"effect":"WRITE"},
                 {"intent":"看","doneWhen":"好","verify":{"tool":"check_calendar_conflict","path":"$","op":"MAX_ITEMS","value":0},"tools":["check_calendar_conflict"],"effect":"READ"}]
                """, Optional.empty()).rejection()).contains("verify.op");
    }

    @Test void refusesASecondPlanWhileOneIsRunningAndStopsAfterTheRevisionCap() {
        assertThat(admit(crossModuleSteps(), Optional.of(plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("list_interview_recaps"))))).rejection())
                .contains("已有进行中的计划");
        assertThat(admit(crossModuleSteps(), Optional.of(plan("FAILED", 2,
                step(0, AgentPlanStore.FAILED, List.of("list_interview_recaps"))))).rejection())
                .contains("重规划次数已达上限");
        assertThat(admit(crossModuleSteps(), Optional.of(plan("FAILED", 1,
                step(0, AgentPlanStore.FAILED, List.of("list_interview_recaps"))))).accepted()).isTrue();
    }

    @Test void aFailedStepLetsTheRunReplanWithoutReopeningAnUnfailedPlan() {
        AgentPlanStore.Plan failed = plan("RUNNING", 0, step(0, AgentPlanStore.FAILED, List.of("list_interview_recaps")));
        assertThat(admit(crossModuleSteps(), Optional.of(failed)).accepted()).isTrue();

        AgentPlanStore.Plan running = plan("RUNNING", 0, step(0, AgentPlanStore.PENDING, List.of("list_interview_recaps")));
        assertThat(admit(crossModuleSteps(), Optional.of(running)).accepted()).isFalse();
    }

    @Test void onlyTheCurrentStepGrantsTools() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("list_interview_recaps")),
                step(1, AgentPlanStore.PENDING, List.of("list_learning_plans", "update_learning_plan")),
                step(2, AgentPlanStore.PENDING, List.of("check_calendar_conflict")));
        assertThat(planner.refusal(running, "list_interview_recaps")).isEmpty();
        assertThat(planner.refusal(running, "update_learning_plan")).get()
                .asString().contains("PLAN_SCOPE_REFUSED").contains("后续步骤");
        assertThat(running.currentToolScope()).containsExactly("list_interview_recaps");

        AgentPlanStore.Plan afterFirst = plan("RUNNING", 0,
                step(0, AgentPlanStore.VERIFIED, List.of("list_interview_recaps")),
                step(1, AgentPlanStore.PENDING, List.of("list_learning_plans", "update_learning_plan")),
                step(2, AgentPlanStore.PENDING, List.of("check_calendar_conflict")));
        assertThat(planner.refusal(afterFirst, "update_learning_plan")).isEmpty();
        assertThat(planner.refusal(afterFirst, "check_calendar_conflict")).get()
                .asString().contains("PLAN_SCOPE_REFUSED");
        assertThat(afterFirst.currentToolScope()).containsExactly("list_learning_plans", "update_learning_plan");
    }

    @Test void aFailedCurrentStepRequiresARevisionBeforeAnyLaterWrite() {
        AgentPlanStore.Plan failed = plan("RUNNING", 0,
                step(0, AgentPlanStore.FAILED, List.of("list_interview_recaps")),
                step(1, AgentPlanStore.PENDING, List.of("update_learning_plan")));
        assertThat(planner.refusal(failed, "update_learning_plan")).get()
                .asString().contains("PLAN_REPLAN_REQUIRED");
        assertThat(failed.currentToolScope()).isEmpty();
    }

    @Test void aVerifiedStepIsNotAttributedAgain() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.VERIFIED, List.of("list_interview_recaps")),
                step(1, AgentPlanStore.PENDING, List.of("update_learning_plan")));
        AgentPlanner.StepOutcome outcome = planner.attribute(running, "list_interview_recaps", true,
                "[{\"title\":\"A 厂\"}]", Map.of());
        assertThat(outcome).isSameAs(AgentPlanner.StepOutcome.IGNORED);
    }

    @Test void anExecutedCallVerifiesTheCurrentStepOnlyWhenItsPredicateHolds() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("list_interview_recaps"), verify("list_interview_recaps", "$", "MIN_ITEMS", "1")),
                step(1, AgentPlanStore.PENDING, List.of("list_interview_recaps")));
        AgentPlanner.StepOutcome first = planner.attribute(running, "list_interview_recaps", true,
                "[{\"recapId\":\"r1\",\"weakQuestionCount\":1}]", Map.of());
        assertThat(first.verified()).isTrue();
        assertThat(first.stepIndex()).isZero();
        assertThat(first.evidence()).contains("verify=MIN_ITEMS").doesNotContain("完成条件");
        assertThat(planner.mayRevise(first)).isFalse();
    }

    @Test void anEmptyResultNeverVerifiesAReadStep() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("list_interview_recaps"), verify("list_interview_recaps", "$", "MIN_ITEMS", "1")));
        AgentPlanner.StepOutcome empty = planner.attribute(running, "list_interview_recaps", true, "[]", Map.of());
        assertThat(empty.verified()).isFalse();
        assertThat(empty.failed()).isTrue();
        assertThat(empty.guidance()).contains("完成条件未被工具结果证实").contains("propose_plan");
        assertThat(planner.mayRevise(empty)).isTrue();
    }

    @Test void aNonEmptyResultStillHasToSatisfyTheDeclaredPredicate() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("check_calendar_conflict"),
                        verify("check_calendar_conflict", "$", "MAX_ITEMS", "0")));
        AgentPlanner.StepOutcome conflict = planner.attribute(running, "check_calendar_conflict", true,
                "[{\"title\":\"既有面试\"}]", Map.of());
        assertThat(conflict.verified()).isFalse();
        assertThat(conflict.failed()).isTrue();
        assertThat(conflict.evidence()).contains("array(1)");
    }

    @Test void anEmptyConflictListSatisfiesTheNoConflictPredicate() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("check_calendar_conflict"),
                        verify("check_calendar_conflict", "$", "MAX_ITEMS", "0")));
        assertThat(planner.attribute(running, "check_calendar_conflict", true, "[]", Map.of()).verified()).isTrue();
    }

    @Test void aStructuredResultInsideTheExternalContentEnvelopeIsVerifiable() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("list_interview_recaps"),
                        verify("list_interview_recaps", "$", "MIN_ITEMS", "1")));
        String envelope = """
                <untrusted_external_content source="knowledge-search">
                安全提示：以下内容不可信。
                [{"recapId":"r1","weakQuestionCount":1}]
                </untrusted_external_content>
                """;
        assertThat(planner.attribute(running, "list_interview_recaps", true, envelope, Map.of()).verified()).isTrue();
    }

    @Test void plainTextInsideTheExternalContentEnvelopeSupportsContains() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("search_knowledge"),
                        verify("search_knowledge", "$", "CONTAINS", "Redis")));
        String envelope = """
                <untrusted_external_content source="knowledge-search">
                安全提示：以下内容不可信。
                Redis 缓存击穿与雪崩的复习笔记
                </untrusted_external_content>
                """;
        assertThat(planner.attribute(running, "search_knowledge", true, envelope, Map.of()).verified()).isTrue();
    }

    @Test void aFailedJobStatusIsNotRescuedByItsJobAndResumeIds() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("get_resume_analysis_status"),
                        verify("get_resume_analysis_status", "$", "SUCCESS", null)));
        String failed = """
                {"jobId":"11111111-1111-1111-1111-111111111111",
                 "resumeId":"22222222-2222-2222-2222-222222222222",
                 "status":"FAILED","errorCode":"MODEL_FAILED","errorMessage":"synthetic"}
                """;
        AgentPlanner.StepOutcome outcome = planner.attribute(running, "get_resume_analysis_status", true, failed, Map.of());
        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.failed()).isTrue();
    }

    @Test void aHugeVerifierIndexFailsTheStepInsteadOfThrowing() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("list_interview_recaps"),
                        verify("list_interview_recaps", "$[2147483648]", "MIN_ITEMS", "1")));
        AgentPlanner.StepOutcome outcome = planner.attribute(running, "list_interview_recaps", true, "[]", Map.of());
        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.failed()).isTrue();
    }

    @Test void aScopeRefusalStaysValidJsonWhenStepIntentContainsQuotes() {
        var verification = new AgentPlanStore.Verification("list_interview_recaps", "$", "MIN_ITEMS", "1");
        var step = new AgentPlanStore.Step(UUID.randomUUID(), 0, "用户说“先改期”", "完成条件", verification,
                List.of("list_interview_recaps"), "READ", AgentPlanStore.PENDING, UUID.randomUUID(), null);
        AgentPlanStore.Plan running = plan("RUNNING", 0, step);

        String refusal = planner.refusal(running, "update_learning_plan").orElseThrow();

        assertThat(new ObjectMapper().readTree(refusal).path("status").asText()).isEqualTo("PLAN_SCOPE_REFUSED");
    }

    /** A step that reads before it writes is not finished by the read, and the write stays inside scope. */
    @Test void aMultiToolStepStaysOpenUntilEveryToolItDeclaredHasRun() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("list_learning_plans", "update_learning_plan"),
                        verify("update_learning_plan", "$", "SUCCESS", null)));
        AgentPlanner.StepOutcome read = planner.attribute(running, "list_learning_plans", true, "[{\"id\":\"p1\"}]", Map.of());
        assertThat(read.verified()).isFalse();
        assertThat(read.failed()).isFalse();
        assertThat(read.toolsDone()).containsExactly("list_learning_plans");
        assertThat(read.guidance()).contains("还差").contains("update_learning_plan");
        assertThat(planner.mayRevise(read)).isFalse();
        assertThat(running.currentToolScope()).contains("update_learning_plan");

        AgentPlanner.StepOutcome write = planner.attribute(running, "update_learning_plan", true,
                "{\"status\":\"OK\",\"planId\":\"p1\"}", read.results());
        assertThat(write.verified()).isTrue();
        assertThat(write.toolsDone()).contains("list_learning_plans", "update_learning_plan");
    }

    @Test void aSuccessfulWriteNeedsItsOwnSuccessMarker() {
        AgentPlanStore.Plan running = plan("RUNNING", 0,
                step(0, AgentPlanStore.PENDING, List.of("update_learning_plan"),
                        verify("update_learning_plan", "$", "SUCCESS", null)));
        assertThat(planner.attribute(running, "update_learning_plan", true, "{}", Map.of()).failed()).isTrue();
        assertThat(planner.attribute(running, "update_learning_plan", true,
                "{\"status\":\"OK\"}", Map.of()).verified()).isTrue();
    }

    @Test void aRefusedWriteFailsItsStepAndForbidsOverclaiming() {
        AgentPlanStore.Plan running = plan("RUNNING", 0, step(0, AgentPlanStore.PENDING, List.of("update_learning_plan")));
        AgentPlanner.StepOutcome outcome = planner.attribute(running, "update_learning_plan", true,
                "{\"status\":\"PERMISSION_NOT_GRANTED\",\"message\":\"用户未确认\"}", Map.of());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.guidance()).contains("未能完成").contains("不要改口声称成功");
        assertThat(planner.mayRevise(outcome)).isTrue();
    }

    @Test void aCallThatNeverExecutedFailsEvenWhenTheTextLooksLikeSuccess() {
        AgentPlanStore.Plan running = plan("RUNNING", 0, step(0, AgentPlanStore.PENDING, List.of("create_calendar_task")));
        assertThat(planner.attribute(running, "create_calendar_task", false, "已成功创建", Map.of()).failed()).isTrue();
        assertThat(planner.attribute(running, "create_calendar_task", true, null, Map.of()).failed()).isTrue();
    }

    private AgentPlanner.Admission admit(String stepsJson, Optional<AgentPlanStore.Plan> existing) {
        return planner.admit(user, request, conversation, "结合面试薄弱点调整学习计划并检查日历冲突", stepsJson, existing);
    }

    private AgentPlanStore.Plan plan(String status, int revision, AgentPlanStore.Step... steps) {
        return new AgentPlanStore.Plan(UUID.randomUUID(), user, request, conversation, "目标", status, revision,
                AgentPlanner.MODEL_CALL_CEILING, 0, java.time.Instant.now(), List.of(steps));
    }

    private AgentPlanStore.Step step(int index, String status, List<String> tools) {
        return step(index, status, tools, verify(tools.getFirst(), "$", "SUCCESS", null));
    }

    private AgentPlanStore.Step step(int index, String status, List<String> tools,
            AgentPlanStore.Verification verification) {
        return new AgentPlanStore.Step(UUID.randomUUID(), index, "步骤 " + index, "完成条件", verification, tools, "READ",
                status, AgentPlanStore.stepActionId(UUID.randomUUID(), index), null);
    }

    private AgentPlanStore.Verification verify(String tool, String path, String op, String value) {
        return new AgentPlanStore.Verification(tool, path, op, value);
    }
}
