package com.projectardor.learning.practice.mockinterview.service;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Service;

import com.projectardor.learning.practice.mockinterview.domain.DifficultyFeedback;
import com.projectardor.platform.model.llm.service.LlmGateway;

/**
 * Micrometer instrumentation for adaptive interviews, named {@code ardor.interview.<metric>}.
 *
 * <p>Competency names come from the model and would make an unbounded tag, so domain coverage is a
 * per-session summary of how many competencies were actually reached rather than a counter per skill.
 * Recording is fire-and-forget: telemetry must never decide whether an interview advances.
 */
@Service
public final class InterviewMetrics {

    private final MeterRegistry registry;
    private final Counter sessionsStarted;
    private final Counter sessionsCompleted;
    private final DistributionSummary competenciesReached;

    public InterviewMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.sessionsStarted = Counter.builder("ardor.interview.sessions.started")
                .description("Adaptive interviews started")
                .register(registry);
        this.sessionsCompleted = Counter.builder("ardor.interview.sessions.completed")
                .description("Adaptive interviews that produced a saved report")
                .register(registry);
        this.competenciesReached = DistributionSummary.builder("ardor.interview.session.competencies")
                .description("Distinct competencies actually asked in a completed interview")
                .register(registry);
    }

    public void recordStart() {
        try {
            sessionsStarted.increment();
        } catch (RuntimeException ignored) { }
    }

    public void recordCompletion(int competenciesAsked) {
        try {
            sessionsCompleted.increment();
            competenciesReached.record(competenciesAsked);
        } catch (RuntimeException ignored) { }
    }

    public void recordQuestion(String questionType, String origin, int difficulty, long durationNanos) {
        try {
            Timer.builder("ardor.interview.question.duration")
                    .description("Time to produce one question, including the model call when one is needed")
                    .tag("type", questionType == null ? "unknown" : questionType)
                    .tag("origin", origin == null ? "unknown" : origin)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Duration.ofNanos(Math.max(0, durationNanos)));
            Counter.builder("ardor.interview.questions")
                    .description("Questions asked, by the difficulty the policy chose")
                    .tag("difficulty", String.valueOf(difficulty))
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) { }
    }

    public void recordEvaluation(String outcome, long durationNanos) {
        try {
            Timer.builder("ardor.interview.evaluation.duration")
                    .description("Time to score one answer against its stored rubric")
                    .tag("outcome", outcome == null ? "unknown" : outcome)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Duration.ofNanos(Math.max(0, durationNanos)));
        } catch (RuntimeException ignored) { }
    }

    public void recordDifficultyFeedback(DifficultyFeedback feedback) {
        if (feedback == null) return;
        try {
            Counter.builder("ardor.interview.difficulty.feedback")
                    .description("Candidate ratings of how hard the question felt")
                    .tag("feedback", feedback.name())
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) { }
    }

    /** Tokens the provider reported for one interview model call, by the stage that spent them. */
    public void recordTokens(String stage, LlmGateway.Usage usage) {
        if (usage == null) return;
        try {
            Counter.builder("ardor.interview.llm.tokens.input")
                    .description("Input tokens reported by the provider for interview model calls")
                    .tag("stage", stage)
                    .register(registry)
                    .increment(usage.inputTokens());
            Counter.builder("ardor.interview.llm.tokens.output")
                    .description("Output tokens reported by the provider for interview model calls")
                    .tag("stage", stage)
                    .register(registry)
                    .increment(usage.outputTokens());
        } catch (RuntimeException ignored) { }
    }
}