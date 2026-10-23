package com.projectardor.assistant.execution.graph;

import java.util.List;

import com.projectardor.assistant.execution.service.ToolIntentPolicy;

/**
 * A bounded, read-only agent graph.
 *
 * <p>The runtime owns the control flow; the model only supplies phase content. This keeps
 * planning, evidence gathering, reflection and answering auditable and testable. A gather
 * step must declare the tools it used; any write tool is rejected here, not by prompt wording.
 */
public final class AgentGraphRuntime {

    public enum Node {
        PLAN, GATHER, OBSERVE, REFLECT, ANSWER, COMPLETED, FAILED
    }

    public record Budget(int maxGatherRounds, int maxToolCalls) {
        public Budget {
            if (maxGatherRounds < 1 || maxGatherRounds > 8) throw new IllegalArgumentException("maxGatherRounds 必须在 1–8");
            if (maxToolCalls < 1 || maxToolCalls > 24) throw new IllegalArgumentException("maxToolCalls 必须在 1–24");
        }
    }

    public record GatherResult(String summary, List<String> tools) {
        public GatherResult {
            if (summary == null || summary.isBlank()) throw new IllegalArgumentException("gather summary 不能为空");
            if (tools == null) throw new IllegalArgumentException("gather tools 不能为空");
            List<String> invalid = tools.stream().filter(tool -> !ToolIntentPolicy.isRead(tool)).toList();
            if (!invalid.isEmpty()) throw new IllegalArgumentException("只读图不能执行写工具: " + invalid);
        }
    }

    public record Checkpoint(String runId, Node node, int round, int toolCalls,
                             String plan, List<GatherResult> gathered, String reflection, String answer) {
        public Checkpoint {
            gathered = gathered == null ? List.of() : List.copyOf(gathered);
        }
    }

    public record Result(String answer, int rounds, int toolCalls, Node finalNode) {
    }

    public interface Model {
        String plan(String request);

        GatherResult gather(String request, String plan, int round);

        String reflect(String request, String plan, List<GatherResult> gathered, int round);

        String answer(String request, String plan, List<GatherResult> gathered, String reflection);
    }

    public interface Observer {
        default void phase(Node node, String label) {}
        default void phaseFinished(Node node, long elapsedMs, boolean success) {}
        default void observation(GatherResult result) {}
        default void completed(Result result) {}
    }

    public interface CheckpointStore {
        Checkpoint load(String runId);

        void save(Checkpoint checkpoint);
    }

    private final Model model;
    private final Observer observer;
    private final CheckpointStore checkpoints;
    private final Budget budget;

    public AgentGraphRuntime(Model model, Observer observer, CheckpointStore checkpoints, Budget budget) {
        this.model = model;
        this.observer = observer == null ? new Observer() {} : observer;
        this.checkpoints = checkpoints == null ? new NoopCheckpointStore() : checkpoints;
        this.budget = budget == null ? new Budget(3, 12) : budget;
    }

    public Result run(String runId, String request) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId 不能为空");
        if (request == null || request.isBlank()) throw new IllegalArgumentException("request 不能为空");

        Checkpoint checkpoint = checkpoints.load(runId);
        if (checkpoint == null) checkpoint = new Checkpoint(runId, Node.PLAN, 0, 0, "", List.of(), "", "");

        while (true) {
            Checkpoint current = checkpoint;
            switch (checkpoint.node()) {
                case PLAN -> {
                    String plan = phase(Node.PLAN, "制定执行计划", () -> requiredText(model.plan(request), "plan"));
                    checkpoint = new Checkpoint(runId, Node.GATHER, 1, 0, plan, List.of(), "", "");
                }
                case GATHER -> {
                    if (checkpoint.round() > budget.maxGatherRounds()) {
                        checkpoint = new Checkpoint(runId, Node.ANSWER, checkpoint.round(), checkpoint.toolCalls(),
                                checkpoint.plan(), checkpoint.gathered(), "达到最大检索轮次", checkpoint.answer());
                        continue;
                    }
                    GatherResult gathered = phase(Node.GATHER, "读取第 " + checkpoint.round() + " 轮证据",
                            () -> {
                                GatherResult value = model.gather(request, current.plan(), current.round());
                                if (value == null) throw new IllegalStateException("GATHER 没有返回证据");
                                int calls = current.toolCalls() + value.tools().size();
                                if (calls > budget.maxToolCalls()) {
                                    throw new IllegalStateException("超过只读工具预算: " + calls + " > " + budget.maxToolCalls());
                                }
                                return value;
                            });
                    int toolCalls = checkpoint.toolCalls() + gathered.tools().size();
                    List<GatherResult> all = new java.util.ArrayList<>(checkpoint.gathered());
                    all.add(gathered);
                    observer.observation(gathered);
                    checkpoint = new Checkpoint(runId, Node.OBSERVE, checkpoint.round(), toolCalls,
                            checkpoint.plan(), all, checkpoint.reflection(), checkpoint.answer());
                }
                case OBSERVE -> {
                    phase(Node.OBSERVE, "整理本轮证据", () -> null);
                    checkpoint = new Checkpoint(runId, Node.REFLECT, checkpoint.round(), checkpoint.toolCalls(),
                            checkpoint.plan(), checkpoint.gathered(), checkpoint.reflection(), checkpoint.answer());
                }
                case REFLECT -> {
                    String reflection = phase(Node.REFLECT, "判断证据是否足够",
                            () -> requiredText(model.reflect(request, current.plan(), current.gathered(), current.round()), "reflection"));
                    boolean needMore = reflection.startsWith("NEED_MORE");
                    Node next = needMore && checkpoint.round() < budget.maxGatherRounds() ? Node.GATHER : Node.ANSWER;
                    checkpoint = new Checkpoint(runId, next, needMore && next == Node.GATHER ? checkpoint.round() + 1 : checkpoint.round(),
                            checkpoint.toolCalls(), checkpoint.plan(), checkpoint.gathered(), reflection, checkpoint.answer());
                }
                case ANSWER -> {
                    String answer = phase(Node.ANSWER, "生成最终回答",
                            () -> requiredText(model.answer(request, current.plan(), current.gathered(), current.reflection()), "answer"));
                    Result result = new Result(answer, checkpoint.round(), checkpoint.toolCalls(), Node.COMPLETED);
                    checkpoints.save(new Checkpoint(runId, Node.COMPLETED, checkpoint.round(), checkpoint.toolCalls(),
                            checkpoint.plan(), checkpoint.gathered(), checkpoint.reflection(), answer));
                    observer.completed(result);
                    return result;
                }
                case COMPLETED, FAILED -> {
                    if (checkpoint.answer() == null || checkpoint.answer().isBlank()) {
                        throw new IllegalStateException("终态 checkpoint 缺少 answer");
                    }
                    Result result = new Result(checkpoint.answer(), checkpoint.round(), checkpoint.toolCalls(), checkpoint.node());
                    observer.completed(result);
                    return result;
                }
            }
            checkpoints.save(checkpoint);
        }
    }

    private static String requiredText(String value, String phase) {
        if (value == null || value.isBlank()) throw new IllegalStateException(phase + " 不能为空");
        return value;
    }

    private <T> T phase(Node node, String label, java.util.function.Supplier<T> work) {
        observer.phase(node, label);
        long started = System.nanoTime();
        boolean success = false;
        try {
            T value = work.get();
            success = true;
            return value;
        } finally {
            observer.phaseFinished(node, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), success);
        }
    }

    private static final class NoopCheckpointStore implements CheckpointStore {
        @Override public Checkpoint load(String runId) { return null; }
        @Override public void save(Checkpoint checkpoint) { }
    }
}
