package com.projectardor.agent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.projectardor.common.web.ResourceNotFoundException;

/** Owner-scoped, bounded live output. Completed reasoning is saved in the message trace. */
@Service
public class AgentStreamProgress {
    private static final int OUTPUT_LIMIT = 32_000;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    public AgentStreamProgress() { this(Clock.systemUTC()); }
    AgentStreamProgress(Clock clock) { this.clock = clock; }

    public void begin(UUID userId, UUID requestId) { entries.put(requestId, new Entry(userId, clock.instant())); }
    public void thinking(UUID requestId, String text) { observe(requestId, "THINKING", text); }
    public void answer(UUID requestId, String text) { observe(requestId, "OUTPUT", text); }
    public void tool(UUID requestId, String label) {
        Entry entry = entries.get(requestId);
        if (entry != null) synchronized (entry) {
            if (entry.finishedAt != null) return;
            entry.phase = "TOOL"; entry.label = label;
        }
    }
    public void waiting(UUID requestId) {
        Entry entry = entries.get(requestId);
        if (entry != null) synchronized (entry) {
            if (entry.finishedAt != null) return;
            entry.phase = "WAITING"; entry.label = "等待模型输出"; entry.lastData = clock.instant();
        }
    }
    public void finish(UUID requestId, boolean failed) {
        Entry entry = entries.get(requestId);
        if (entry != null) synchronized (entry) {
            if (entry.finishedAt != null) return;
            entry.phase = failed ? "FAILED" : "COMPLETED"; entry.finishedAt = clock.instant();
        }
    }
    private void observe(UUID requestId, String phase, String text) {
        if (text == null || text.isEmpty()) return;
        Entry entry = entries.get(requestId);
        if (entry == null) return;
        synchronized (entry) {
            if (entry.finishedAt != null) return;
            entry.phase = phase; entry.lastData = clock.instant();
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
                    entry.firstThinkingMs, entry.firstOutputMs, silentSeconds, entry.truncated);
        }
    }
    @Scheduled(fixedDelay = 60_000)
    public void expire() {
        Instant cutoff = clock.instant().minus(Duration.ofHours(2));
        entries.entrySet().removeIf(item -> item.getValue().startedAt.isBefore(cutoff));
    }
    public record View(String phase, String label, String thinkingOutput, String answerPartial,
                       Long firstThinkingMs, Long firstOutputMs, long silentSeconds, boolean truncated) {}
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
        Entry(UUID userId, Instant now) { this.userId = userId; this.startedAt = now; this.lastData = now; }
    }
}
