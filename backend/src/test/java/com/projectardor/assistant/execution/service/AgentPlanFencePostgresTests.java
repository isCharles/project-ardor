package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plan and its verification have to survive in the same execution record as the tool calls, under
 * kinds the database actually accepts. Mocks cannot prove that: a rejected span kind is only a log line.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AgentPlanFencePostgresTests {

    private static final String GOAL = "结合面试薄弱点调整学习计划并排入日历";
    private static final String STEPS = """
            [{"intent":"读出最近面试的薄弱点","doneWhen":"已得到至少一条薄弱点结论","verify":{"tool":"list_interview_recaps","path":"$","op":"MIN_ITEMS","value":1},"tools":["list_interview_recaps"],"effect":"READ"},
             {"intent":"按薄弱点调整学习安排","doneWhen":"学习计划已更新","verify":{"tool":"update_learning_plan","path":"$","op":"SUCCESS"},"tools":["update_learning_plan"],"effect":"WRITE"},
             {"intent":"把复习安排进日历","doneWhen":"日程已创建","verify":{"tool":"create_calendar_task","path":"$","op":"SUCCESS"},"tools":["create_calendar_task"],"effect":"WRITE"}]
            """;

    static class ProbeTools {
        final AtomicInteger recaps = new AtomicInteger();
        final AtomicInteger learning = new AtomicInteger();
        final AtomicInteger offPlan = new AtomicInteger();

        @Tool(name = "list_interview_recaps", value = "列出面经")
        public java.util.List<java.util.Map<String, Object>> listInterviewRecaps() {
            recaps.incrementAndGet();
            return java.util.List.of(java.util.Map.of("recapId", "r1", "weakQuestionCount", 1));
        }

        @Tool(name = "update_learning_plan", value = "调整学习计划")
        public String updateLearningPlan(@P("说明") String note) {
            learning.incrementAndGet();
            return "{\"status\":\"OK\"}";
        }

        @Tool(name = "create_calendar_task", value = "创建日程")
        public String createCalendarTask(@P("标题") String title) {
            return "{\"status\":\"OK\"}";
        }

        @Tool(name = "update_memory", value = "改写记忆")
        public String updateMemory(@P("内容") String content) {
            offPlan.incrementAndGet();
            return "must never run";
        }
    }

    @Test void planStepsAndRefusalsAreRecordedInTheRunTrace() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        String schema = "ardor_plan_fence_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                    "ardor", "acceptance-only");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID user = UUID.randomUUID(), conversation = UUID.randomUUID(), run = UUID.randomUUID();
            jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", user, user + "@ardor.invalid", "synthetic-only");
            jdbc.update("INSERT INTO conversations(id,user_id) VALUES(?,?)", conversation, user);
            jdbc.update("INSERT INTO agent_runs(id,user_id,conversation_id,request_hash,message,status) VALUES(?,?,?,?,'Synthetic turn','RUNNING')",
                    run, user, conversation, "x".repeat(64));
            var planStore = new AgentPlanStore(jdbc);
            var traces = new AgentTraceStore(jdbc, JsonMapper.builder().build(), null, true, 30);
            var tracer = AgentRunTracer.begin(traces, user, run, conversation, "synthetic-model", AgentRunTracer.PROMPT_VERSION);
            var planning = new PlanningAgentTools(user, run, conversation,
                    new AgentPlanner(JsonMapper.builder().build()), planStore, tracer);
            assertThat(planning.proposePlan(GOAL, STEPS)).containsEntry("accepted", true);

            var approvals = mock(AgentToolApprovals.class);
            when(approvals.canPropose(eq(user), any())).thenReturn(true);
            when(approvals.canRead(eq(user), any())).thenReturn(true);
            var permissions = mock(AgentPermissionGrants.class);
            when(permissions.autoApproves(eq(user), anyString())).thenReturn(true);
            when(permissions.requiresConfirmationForBatch(eq(user), anyInt())).thenReturn(false);
            var tools = new ProbeTools();
            Map<String, ToolExecutor> byName = new java.util.LinkedHashMap<>();
            ApprovedAgentTools.wrap(tools, user, run, approvals, null, new AgentToolTimings(), waiting -> {},
                            GOAL, permissions, null, conversation, false, tracer, planning)
                    .forEach((specification, executor) -> byName.put(specification.name(), executor));

            assertThat(byName.get("list_interview_recaps").execute(request("list_interview_recaps"), null))
                    .contains("recapId");
            assertThat(tools.recaps).hasValue(1);
            byName.get("update_learning_plan").execute(request("update_learning_plan"), null);
            assertThat(tools.learning).hasValue(1);
            assertThat(byName.get("update_memory").execute(request("update_memory"), null)).contains("PLAN_SCOPE_REFUSED");
            assertThat(tools.offPlan).as("an off-plan write never reaches the business service").hasValue(0);
            planning.finish("COMPLETED");

            List<String> stepStatuses = jdbc.queryForList("SELECT status FROM agent_plan_steps ORDER BY step_index", String.class);
            assertThat(stepStatuses).as("the current step had to finish before the next one could write")
                    .containsExactly("VERIFIED", "VERIFIED", "SKIPPED");
            assertThat(jdbc.queryForObject("SELECT status FROM agent_plans WHERE id = (SELECT id FROM agent_plans LIMIT 1)",
                    String.class)).isEqualTo("FAILED");
            var kinds = jdbc.queryForList("SELECT kind FROM agent_trace_spans WHERE run_id = ?", String.class, run);
            assertThat(kinds).contains("PLAN", "PLAN_STEP");
            var planSpan = traces.find(user, run).stream().filter(span -> "PLAN".equals(span.kind())).findFirst().orElseThrow();
            assertThat(planSpan.attributes()).containsEntry("stepCount", 3).containsEntry("verifiedSteps", 2);
            assertThat(planSpan.name()).isEqualTo("agent.plan");
            var stepSpan = traces.find(user, run).stream().filter(span -> "PLAN_STEP".equals(span.kind())).findFirst().orElseThrow();
            assertThat(stepSpan.attributes()).containsEntry("verified", true);
            assertThat(stepSpan.status()).isEqualTo("COMPLETED");
            var blocked = traces.find(user, run).stream()
                    .filter(span -> "TOOL".equals(span.kind()) && "PLAN_SCOPE_REFUSED".equals(span.errorCode()))
                    .findFirst();
            assertThat(blocked).as("the refused write leaves its own record").isPresent();
            assertThat(blocked.orElseThrow().attributes()).containsEntry("executed", false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_trace_spans WHERE run_id = ? AND kind = 'PLAN'",
                    Integer.class, run)).isEqualTo(1);
        } finally {
            root.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    /**
     * The plan lookups bind their parameters in predicate order on a real database, so a second
     * proposal in the same run is refused by the durable plan rather than silently stacking revisions.
     */
    @Test void aSecondProposalInTheSameRunIsRefusedByTheStoredPlan() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        String schema = "ardor_plan_lookup_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                    "ardor", "acceptance-only");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID user = UUID.randomUUID(), foreign = UUID.randomUUID();
            UUID conversation = UUID.randomUUID(), run = UUID.randomUUID();
            for (UUID id : new UUID[]{user, foreign}) {
                jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", id, id + "@ardor.invalid", "synthetic-only");
            }
            jdbc.update("INSERT INTO conversations(id,user_id) VALUES(?,?)", conversation, user);
            jdbc.update("INSERT INTO agent_runs(id,user_id,conversation_id,request_hash,message,status) VALUES(?,?,?,?,'Synthetic turn','RUNNING')",
                    run, user, conversation, "y".repeat(64));
            var store = new AgentPlanStore(jdbc);
            var traces = new AgentTraceStore(jdbc, JsonMapper.builder().build(), null, true, 30);
            var tracer = AgentRunTracer.begin(traces, user, run, conversation, "synthetic-model", AgentRunTracer.PROMPT_VERSION);
            var planning = new PlanningAgentTools(user, run, conversation,
                    new AgentPlanner(JsonMapper.builder().build()), store, tracer);

            assertThat(planning.proposePlan(GOAL, STEPS)).containsEntry("accepted", true);
            // Distinct user and request ids are what made a swapped binding order invisible in mocks.
            var stored = store.findByRequest(user, run).orElseThrow();
            assertThat(stored.goal()).isEqualTo(GOAL);
            assertThat(stored.steps()).hasSize(3);
            assertThat(store.findByRequest(foreign, run)).as("another owner never sees this plan").isEmpty();
            assertThat(store.findActiveByConversation(user, conversation)).map(AgentPlanStore.Plan::id)
                    .contains(stored.id());
            assertThat(store.findActiveByConversation(foreign, conversation)).isEmpty();

            assertThat(planning.proposePlan(GOAL, STEPS)).containsEntry("accepted", false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_plans", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT revision FROM agent_plans LIMIT 1", Integer.class)).isZero();
        } finally {
            root.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static ToolExecutionRequest request(String tool) {
        String arguments = "list_interview_recaps".equals(tool) ? "{}"
                : "{\"note\":\"weakness\",\"content\":\"别的\",\"title\":\"复习\"}";
        return ToolExecutionRequest.builder().id("call-" + tool).name(tool).arguments(arguments).build();
    }
}
