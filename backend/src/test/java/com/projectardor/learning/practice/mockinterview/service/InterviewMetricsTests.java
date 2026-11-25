package com.projectardor.learning.practice.mockinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.projectardor.learning.practice.mockinterview.domain.DifficultyFeedback;
import com.projectardor.platform.model.llm.service.LlmGateway;

class InterviewMetricsTests {

    private MeterRegistry registry;
    private InterviewMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new InterviewMetrics(registry);
    }

    @Test
    void completionRateComesFromStartedVersusCompletedSessions() {
        metrics.recordStart();
        metrics.recordStart();
        metrics.recordStart();
        metrics.recordCompletion(4);

        assertThat(registry.get("ardor.interview.sessions.started").counter().count()).isEqualTo(3.0);
        assertThat(registry.get("ardor.interview.sessions.completed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ardor.interview.session.competencies").summary().count()).isEqualTo(1);
        assertThat(registry.get("ardor.interview.session.competencies").summary().totalAmount()).isEqualTo(4.0);
    }

    @Test
    void coverageIsASummaryPerSessionSoModelInventedSkillNamesCannotBecomeTags() {
        metrics.recordCompletion(5);
        metrics.recordCompletion(2);

        assertThat(registry.get("ardor.interview.session.competencies").summary().count()).isEqualTo(2);
        assertThat(registry.get("ardor.interview.session.competencies").summary().mean()).isEqualTo(3.5);
        assertThat(registry.getMeters()).noneMatch(meter ->
                meter.getId().getTags().stream().anyMatch(tag -> tag.getKey().equals("competency")));
    }

    @Test
    void questionLatencySeparatesBankHitsFromModelCallsAndDifficultyIsCounted() {
        metrics.recordQuestion("TECHNICAL", "BANK", 2, Duration.ofMillis(3).toNanos());
        metrics.recordQuestion("TECHNICAL", "LLM", 4, Duration.ofMillis(900).toNanos());
        metrics.recordQuestion("CODING", "LLM", 4, Duration.ofMillis(1200).toNanos());

        assertThat(registry.get("ardor.interview.question.duration")
                .tag("type", "TECHNICAL").tag("origin", "BANK").timer().count()).isEqualTo(1);
        assertThat(registry.get("ardor.interview.question.duration")
                .tag("type", "TECHNICAL").tag("origin", "LLM").timer()
                .takeSnapshot().max(TimeUnit.MILLISECONDS)).isEqualTo(900.0);
        assertThat(registry.get("ardor.interview.question.duration")
                .tag("type", "CODING").tag("origin", "LLM").timer()
                .takeSnapshot().max(TimeUnit.MILLISECONDS)).isEqualTo(1200.0);
        assertThat(registry.get("ardor.interview.questions").tag("difficulty", "4").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("ardor.interview.questions").tag("difficulty", "2").counter().count()).isEqualTo(1.0);
    }

    @Test
    void evaluationOutcomesAreSeparateSoClarificationsDoNotLookLikeScoringLatency() {
        metrics.recordEvaluation("scored", Duration.ofMillis(700).toNanos());
        metrics.recordEvaluation("clarification", Duration.ofMillis(120).toNanos());
        metrics.recordEvaluation("failed", Duration.ofMillis(30_000).toNanos());

        assertThat(registry.get("ardor.interview.evaluation.duration").tag("outcome", "scored").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("ardor.interview.evaluation.duration").tag("outcome", "clarification").timer()
                .takeSnapshot().max(TimeUnit.MILLISECONDS)).isEqualTo(120.0);
        assertThat(registry.get("ardor.interview.evaluation.duration").tag("outcome", "failed").timer().count())
                .isEqualTo(1);
    }

    @Test
    void candidateDifficultyRatingsAreCountedPerVerdict() {
        metrics.recordDifficultyFeedback(DifficultyFeedback.TOO_HARD);
        metrics.recordDifficultyFeedback(DifficultyFeedback.TOO_HARD);
        metrics.recordDifficultyFeedback(DifficultyFeedback.FITTING);
        metrics.recordDifficultyFeedback(null);

        assertThat(registry.get("ardor.interview.difficulty.feedback").tag("feedback", "TOO_HARD").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("ardor.interview.difficulty.feedback").tag("feedback", "FITTING").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("ardor.interview.difficulty.feedback").tag("feedback", "TOO_EASY").counter()).isNull();
    }

    @Test
    void tokensAreAttributedToTheStageThatSpentThemAndAbsentUsageIsNotZeroUsage() {
        metrics.recordTokens("plan", new LlmGateway.Usage(1500, 400));
        metrics.recordTokens("question", new LlmGateway.Usage(300, 200));
        metrics.recordTokens("question", new LlmGateway.Usage(200, 150));
        metrics.recordTokens("evaluate", null);

        assertThat(registry.get("ardor.interview.llm.tokens.input").tag("stage", "question").counter().count())
                .isEqualTo(500.0);
        assertThat(registry.get("ardor.interview.llm.tokens.output").tag("stage", "question").counter().count())
                .isEqualTo(350.0);
        assertThat(registry.get("ardor.interview.llm.tokens.input").tag("stage", "plan").counter().count())
                .isEqualTo(1500.0);
        assertThat(registry.find("ardor.interview.llm.tokens.input").tag("stage", "evaluate").counter()).isNull();
    }

    @Test
    void aNegativeClockReadingIsClampedInsteadOfPoisoningTheTimer() {
        metrics.recordQuestion("TECHNICAL", "LLM", 3, -5);
        metrics.recordEvaluation("scored", -5);

        assertThat(registry.get("ardor.interview.question.duration").timer().totalTime(TimeUnit.NANOSECONDS))
                .isZero();
        assertThat(registry.get("ardor.interview.evaluation.duration").timer().totalTime(TimeUnit.NANOSECONDS))
                .isZero();
    }

    @Test
    void aFailingRegistryNeverDecidesWhetherTheInterviewAdvances() {
        InterviewMetrics broken = new InterviewMetrics(mock(MeterRegistry.class));

        assertThatCode(() -> {
            broken.recordStart();
            broken.recordCompletion(3);
            broken.recordQuestion("TECHNICAL", "LLM", 3, 10);
            broken.recordEvaluation("scored", 10);
            broken.recordDifficultyFeedback(DifficultyFeedback.TOO_EASY);
            broken.recordTokens("report", new LlmGateway.Usage(10, 20));
        }).doesNotThrowAnyException();
    }

    @Test
    void prometheusExpositionExposesTheInterviewHistogramsAndCounters() {
        var prometheus = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        var prometheusMetrics = new InterviewMetrics(prometheus);
        prometheusMetrics.recordStart();
        prometheusMetrics.recordQuestion("TECHNICAL", "LLM", 3, Duration.ofMillis(800).toNanos());
        prometheusMetrics.recordEvaluation("scored", Duration.ofMillis(600).toNanos());
        prometheusMetrics.recordCompletion(3);
        prometheusMetrics.recordTokens("evaluate", new LlmGateway.Usage(900, 250));

        assertThat(prometheus.scrape())
                .contains("ardor_interview_question_duration_seconds_bucket")
                .contains("ardor_interview_evaluation_duration_seconds_bucket")
                .contains("ardor_interview_sessions_started_total")
                .contains("ardor_interview_sessions_completed_total")
                .contains("ardor_interview_llm_tokens_input_total");
    }
}