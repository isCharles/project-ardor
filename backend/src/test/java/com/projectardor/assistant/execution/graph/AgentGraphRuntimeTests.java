package com.projectardor.assistant.execution.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

class AgentGraphRuntimeTests {
    @Test void reportsAStartedPhaseOnlyAfterItsWorkFinishesAndMarksFailuresTruthfully() {
        List<String> events = new ArrayList<>();
        var observer = new AgentGraphRuntime.Observer() {
            public void phase(AgentGraphRuntime.Node node, String label) { events.add(node + ":START"); }
            public void phaseFinished(AgentGraphRuntime.Node node, long elapsed, boolean success) {
                assertThat(elapsed).isGreaterThanOrEqualTo(0);
                events.add(node + (success ? ":DONE" : ":FAILED"));
            }
        };
        var model = org.mockito.Mockito.mock(AgentGraphRuntime.Model.class);
        org.mockito.Mockito.when(model.plan(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
            assertThat(events).containsExactly("PLAN:START");
            throw new IllegalStateException("synthetic planning failure");
        });
        assertThatThrownBy(() -> new AgentGraphRuntime(model, observer, null, null).run("fixture", "read-only"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(events).containsExactly("PLAN:START", "PLAN:FAILED");
    }


    @Test
    void runsPlanGatherObserveReflectAnswerAndStopsWhenEvidenceIsSufficient() {
        RecordingObserver observer = new RecordingObserver();
        InMemoryStore store = new InMemoryStore();
        CountingModel model = new CountingModel();
        var runtime = new AgentGraphRuntime(model, observer, store, new AgentGraphRuntime.Budget(3, 6));

        AgentGraphRuntime.Result result = runtime.run("run-1", "盘点求职准备");

        assertThat(result.answer()).isEqualTo("final answer");
        assertThat(result.rounds()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(model.planCalls).isEqualTo(1);
        assertThat(model.gatherCalls).isEqualTo(2);
        assertThat(model.reflectCalls).isEqualTo(2);
        assertThat(model.answerCalls).isEqualTo(1);
        assertThat(observer.phases).containsExactly("PLAN", "GATHER", "OBSERVE", "REFLECT", "GATHER", "OBSERVE",
                "REFLECT", "ANSWER");
        assertThat(observer.observations).extracting(AgentGraphRuntime.GatherResult::summary)
                .containsExactly("first evidence", "second evidence");
        assertThat(store.saved).isNotEmpty();
        assertThat(store.saved.getLast().node()).isEqualTo(AgentGraphRuntime.Node.COMPLETED);
    }

    @Test
    void stopsAtGatherBudgetAndStillAnswers() {
        RecordingObserver observer = new RecordingObserver();
        CountingModel model = new CountingModel();
        var runtime = new AgentGraphRuntime(model, observer, new InMemoryStore(), new AgentGraphRuntime.Budget(2, 6));

        AgentGraphRuntime.Result result = runtime.run("run-budget", "盘点求职准备");

        assertThat(result.answer()).isEqualTo("final answer");
        assertThat(result.rounds()).isEqualTo(2);
        assertThat(model.gatherCalls).isEqualTo(2);
        assertThat(model.answerCalls).isEqualTo(1);
        assertThat(observer.phases).containsExactly("PLAN", "GATHER", "OBSERVE", "REFLECT", "GATHER", "OBSERVE",
                "REFLECT", "ANSWER");
    }

    @Test
    void rejectsAnyWriteToolInReadOnlyGather() {
        CountingModel model = new CountingModel();
        model.writeTool = true;
        var runtime = new AgentGraphRuntime(model, new RecordingObserver(), new InMemoryStore(),
                new AgentGraphRuntime.Budget(3, 6));

        assertThatThrownBy(() -> runtime.run("run-write", "只读盘点"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只读图不能执行写工具");
    }

    @Test
    void resumesFromCheckpointWithoutCallingPlannerAgain() {
        InMemoryStore store = new InMemoryStore();
        CountingModel model = new CountingModel();
        model.finishAfterFirstGather = true;
        store.save(new AgentGraphRuntime.Checkpoint("run-resume", AgentGraphRuntime.Node.GATHER, 1, 0,
                "resume plan", List.of(), "", ""));
        var runtime = new AgentGraphRuntime(model, new RecordingObserver(), store,
                new AgentGraphRuntime.Budget(3, 6));

        AgentGraphRuntime.Result result = runtime.run("run-resume", "继续盘点");

        assertThat(result.answer()).isEqualTo("final answer");
        assertThat(model.planCalls).isZero();
        assertThat(model.gatherCalls).isEqualTo(1);
    }

    @Test
    void enforcesTotalToolCallBudget() {
        CountingModel model = new CountingModel();
        model.toolsPerGather = List.of("list_resumes", "list_interviews");
        var runtime = new AgentGraphRuntime(model, new RecordingObserver(), new InMemoryStore(),
                new AgentGraphRuntime.Budget(3, 2));

        assertThatThrownBy(() -> runtime.run("run-tools", "只读盘点"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超过只读工具预算");
    }

    private static final class RecordingObserver implements AgentGraphRuntime.Observer {
        final List<String> phases = new ArrayList<>();
        final List<AgentGraphRuntime.GatherResult> observations = new ArrayList<>();

        @Override public void phase(AgentGraphRuntime.Node node, String label) { phases.add(node.name()); }
        @Override public void observation(AgentGraphRuntime.GatherResult result) { observations.add(result); }
    }

    private static final class InMemoryStore implements AgentGraphRuntime.CheckpointStore {
        final Map<String, AgentGraphRuntime.Checkpoint> current = new ConcurrentHashMap<>();
        final List<AgentGraphRuntime.Checkpoint> saved = new ArrayList<>();

        @Override public AgentGraphRuntime.Checkpoint load(String runId) { return current.get(runId); }
        @Override public void save(AgentGraphRuntime.Checkpoint checkpoint) {
            current.put(checkpoint.runId(), checkpoint);
            saved.add(checkpoint);
        }
    }

    private static final class CountingModel implements AgentGraphRuntime.Model {
        int planCalls;
        int gatherCalls;
        int reflectCalls;
        int answerCalls;
        boolean writeTool;
        boolean finishAfterFirstGather;
        List<String> toolsPerGather = List.of("list_resumes", "list_interviews");

        @Override public String plan(String request) {
            planCalls++;
            return "1. 读取证据\n2. 交叉分析";
        }

        @Override public AgentGraphRuntime.GatherResult gather(String request, String plan, int round) {
            gatherCalls++;
            List<String> tools = writeTool && round == 1
                    ? List.of("list_resumes", "create_calendar_task")
                    : round == 1 ? toolsPerGather.subList(0, Math.min(1, toolsPerGather.size())) : List.of("get_resume_analysis", "list_interviews");
            return new AgentGraphRuntime.GatherResult(round == 1 ? "first evidence" : "second evidence", tools);
        }

        @Override public String reflect(String request, String plan, List<AgentGraphRuntime.GatherResult> gathered, int round) {
            reflectCalls++;
            if (finishAfterFirstGather) return "SUFFICIENT";
            return round < 2 ? "NEED_MORE: 还需要面试与学习证据" : "SUFFICIENT";
        }

        @Override public String answer(String request, String plan, List<AgentGraphRuntime.GatherResult> gathered, String reflection) {
            answerCalls++;
            return "final answer";
        }
    }
}
