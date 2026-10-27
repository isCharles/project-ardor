package com.projectardor.assistant.execution.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.projectardor.common.web.ResourceNotFoundException;

/** Owner-scoped, bounded live output. Completed reasoning is saved in the message trace. */
@Service
public class AgentStreamProgress {
    private static final int OUTPUT_LIMIT = 32_000;
    private final Clock clock;
    private final AgentProgressStore store;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    public AgentStreamProgress() { this(Clock.systemUTC(), null); }
    AgentStreamProgress(Clock clock) { this(clock, null); }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentStreamProgress(AgentProgressStore store) { this(Clock.systemUTC(), store); }
    AgentStreamProgress(Clock clock, AgentProgressStore store) { this.clock = clock; this.store = store; }

    public void begin(UUID userId, UUID requestId) { entries.put(requestId, new Entry(userId, clock.instant())); }
    public void thinking(UUID requestId, String text) { observe(requestId, "THINKING", text); }
    public void answer(UUID requestId, String text) { observe(requestId, "OUTPUT", text); }
    String pendingAnswer(UUID requestId) {
        Entry entry = entries.get(requestId);
        if (entry == null) return "";
        synchronized (entry) { return entry.finishedAt == null ? entry.answer.toString() : ""; }
    }
    /** A tool round has ended; its streamed text is process commentary, not the final answer. */
    public void intermediate(UUID requestId, String commentary) {
        Entry entry = entries.get(requestId);
        if (entry != null) synchronized (entry) {
            if (entry.finishedAt != null) return;
            entry.answer.setLength(0);
            if (commentary != null && !commentary.isBlank() && entry.steps.size() < 32)
                entry.steps.add(new Step(commentary, 0, "COMMENTARY", null, null));
            entry.revision++;
        }
    }
    public void context(UUID requestId, AgentContextUsage usage) {
        Entry entry = entries.get(requestId);
        if (entry != null) synchronized (entry) {
            if (entry.finishedAt != null) return;
            entry.contextUsage = usage; entry.revision++;
        }
    }
    public void tool(UUID requestId, String label) {
        Entry entry = entries.get(requestId);
        if (entry != null) synchronized (entry) {
            if (entry.finishedAt != null) return;
            entry.phase = "TOOL"; entry.label = label; entry.revision++;
        }
    }
    public void waiting(UUID requestId) {
        Entry entry = entries.get(requestId);
        if (entry != null) synchronized (entry) {
            if (entry.finishedAt != null) return;
            entry.phase = "WAITING"; entry.label = "等待模型输出"; entry.lastData = clock.instant(); entry.revision++;
        }
    }
    public void finish(UUID requestId, boolean failed) {
        Entry entry = entries.get(requestId);
        if (entry != null) synchronized (entry) {
            if (entry.finishedAt != null) return;
            entry.phase = failed ? "FAILED" : "COMPLETED"; entry.finishedAt = clock.instant(); entry.revision++;
        }
        flush(requestId, entry);
    }
    public void step(UUID requestId, String label, long elapsedMs, String status) {
        step(requestId, label, elapsedMs, status, null, null);
    }
    public void step(UUID requestId, String label, long elapsedMs, String status, Long approvalWaitMs, Long businessMs) {
        Entry entry = entries.get(requestId);
        if (entry != null) synchronized (entry) {
            if (entry.finishedAt != null || entry.steps.size() >= 32) return;
            entry.steps.add(new Step(label, elapsedMs, status, approvalWaitMs, businessMs)); entry.revision++;
        }
    }
    private void observe(UUID requestId, String phase, String text) {
        if (text == null || text.isEmpty()) return;
        Entry entry = entries.get(requestId);
        if (entry == null) return;
        synchronized (entry) {
            if (entry.finishedAt != null) return;
            entry.phase = phase; entry.lastData = clock.instant(); entry.revision++;
            StringBuilder output = phase.equals("THINKING") ? entry.thinking : entry.answer;
            int available = OUTPUT_LIMIT - output.length();
            if (available > 0) output.append(text, 0, Math.min(available, text.length()));
            if (text.length() > available) entry.truncated = true;
            long delay = Duration.between(entry.startedAt, entry.lastData).toMillis();
            if (phase.equals("THINKING") && entry.firstThinkingMs == null) entry.firstThinkingMs = delay;
            if (phase.equals("OUTPUT") && entry.firstOutputMs == null) entry.firstOutputMs = delay;
        }
    }
    public View get(UUID userId, UUID requestId) {
        Entry entry = entries.get(requestId);
        if (entry == null && store != null) {
            AgentProgressStore.Saved saved = store.find(userId, requestId)
                    .orElseThrow(() -> new ResourceNotFoundException("执行进度不存在"));
            Snapshot snapshot = saved.snapshot();
            View view = snapshot.view();
            boolean terminal = !"RUNNING".equals(saved.status());
            long silence = Math.max(0, Duration.between(snapshot.lastData(), clock.instant()).toSeconds());
            String phase = terminal ? saved.status() : !view.phase().equals("TOOL") && silence >= 30 ? "WAITING_DATA" : view.phase();
            return new View(phase, terminal ? "COMPLETED".equals(phase) ? "已完成" : "执行已结束，查看保留的记录" : view.label(),
                    view.thinkingOutput(), view.answerPartial(), view.firstThinkingMs(), view.firstOutputMs(), silence,
                    view.truncated(), view.steps(), terminal ? saved.elapsedMs() : Math.max(0, Duration.between(snapshot.startedAt(), clock.instant()).toMillis()), view.contextUsage());
        }
        if (entry == null || !entry.userId.equals(userId)) throw new ResourceNotFoundException("执行进度不存在");
        synchronized (entry) {
            long silentSeconds = Math.max(0, Duration.between(entry.lastData, clock.instant()).toSeconds());
            boolean waiting = entry.finishedAt == null && !entry.phase.equals("TOOL") && silentSeconds >= 30;
            String phase = waiting ? "WAITING_DATA" : entry.phase;
            String label = waiting ? "上游暂未返回新数据" : switch (phase) {
                case "THINKING" -> "收到模型思考输出";
                case "OUTPUT" -> "正在接收回复";
                case "TOOL" -> entry.label;
                case "COMPLETED" -> "已完成";
                case "FAILED" -> "执行失败";
                default -> "等待模型输出";
            };
            return new View(phase, label, entry.thinking.toString(), entry.answer.toString(),
                    entry.firstThinkingMs, entry.firstOutputMs, silentSeconds, entry.truncated, List.copyOf(entry.steps),
                    Math.max(0, Duration.between(entry.startedAt, entry.finishedAt == null ? clock.instant() : entry.finishedAt).toMillis()), entry.contextUsage);
        }
    }
    @Scheduled(fixedDelay = 1000)
    public void checkpoint() { if (store != null) entries.forEach(this::flush); }
    private void flush(UUID id, Entry entry) {
        if (store == null || entry == null) return;
        Snapshot snapshot;
        synchronized (entry) {
            if (entry.savedRevision >= entry.revision) return;
            snapshot = new Snapshot(entry.revision, entry.startedAt, entry.lastData, get(entry.userId, id));
        }
        try {
            store.save(entry.userId, id, snapshot);
            synchronized (entry) { entry.savedRevision = Math.max(entry.savedRevision, snapshot.revision()); }
        } catch (RuntimeException failure) {
            // A checkpoint failure must not turn a successful business operation into a retry.
            org.slf4j.LoggerFactory.getLogger(AgentStreamProgress.class).warn("Agent progress checkpoint deferred: {}", failure.getClass().getSimpleName());
        }
    }
    @Scheduled(fixedDelay = 60_000)
    public void expire() {
        Instant cutoff = clock.instant().minus(Duration.ofHours(2));
        entries.entrySet().removeIf(item -> item.getValue().startedAt.isBefore(cutoff));
    }
    public record View(String phase, String label, String thinkingOutput, String answerPartial,
                       Long firstThinkingMs, Long firstOutputMs, long silentSeconds, boolean truncated, List<Step> steps, long elapsedMs,
                       AgentContextUsage contextUsage) {
        public View(String phase, String label, String thinkingOutput, String answerPartial,
                Long firstThinkingMs, Long firstOutputMs, long silentSeconds, boolean truncated, List<Step> steps, long elapsedMs) {
            this(phase, label, thinkingOutput, answerPartial, firstThinkingMs, firstOutputMs, silentSeconds, truncated, steps, elapsedMs, null);
        }
    }
    public record Step(String label, long elapsedMs, String status, Long approvalWaitMs, Long businessMs) {}
    public record Snapshot(long revision, Instant startedAt, Instant lastData, View view) {}
    private static final class Entry {
        final UUID userId;
        final Instant startedAt;
        Instant lastData;
        Instant finishedAt;
        String phase = "WAITING";
        String label = "等待模型输出";
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder answer = new StringBuilder();
        Long firstThinkingMs;
        Long firstOutputMs;
        boolean truncated;
        AgentContextUsage contextUsage;
        long revision = 1;
        long savedRevision;
        final List<Step> steps = new ArrayList<>();
        Entry(UUID userId, Instant now) { this.userId = userId; this.startedAt = now; this.lastData = now; }
    }
}
