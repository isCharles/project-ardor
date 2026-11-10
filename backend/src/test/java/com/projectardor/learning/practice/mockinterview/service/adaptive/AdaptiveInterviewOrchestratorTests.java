package com.projectardor.learning.practice.mockinterview.service.adaptive;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.jobsearch.interview.service.OpportunityService;
import com.projectardor.learning.practice.mockinterview.domain.AdaptiveRubric;
import com.projectardor.learning.practice.mockinterview.domain.AnswerKind;
import com.projectardor.learning.practice.mockinterview.domain.CompetencyState;
import com.projectardor.learning.practice.mockinterview.domain.DifficultyFeedback;
import com.projectardor.learning.practice.mockinterview.domain.InterviewAnswer;
import com.projectardor.learning.practice.mockinterview.domain.InterviewCompetencyAssessment;
import com.projectardor.learning.practice.mockinterview.domain.InterviewEvaluation;
import com.projectardor.learning.practice.mockinterview.domain.InterviewModality;
import com.projectardor.learning.practice.mockinterview.domain.InterviewMode;
import com.projectardor.learning.practice.mockinterview.domain.InterviewPolicyDecision;
import com.projectardor.learning.practice.mockinterview.domain.InterviewQuestion;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;
import com.projectardor.learning.practice.mockinterview.domain.InterviewTurnEvaluation;
import com.projectardor.learning.practice.mockinterview.domain.PolicyAction;
import com.projectardor.learning.practice.mockinterview.domain.QuestionOrigin;
import com.projectardor.learning.practice.mockinterview.domain.QuestionTarget;
import com.projectardor.learning.practice.mockinterview.repository.InterviewAnswerRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewCompetencyAssessmentRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewEvaluationRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewPolicyDecisionRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewQuestionRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewSessionRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewTurnEvaluationRepository;
import com.projectardor.learning.practice.mockinterview.service.InterviewMetrics;
import com.projectardor.platform.model.llm.service.LlmCallException;

/**
 * The interview lives in PostgreSQL, so every crash window has to be finishable by the next read.
 * These tests drive the orchestrator against in-memory rows and cut the flow at each window: before
 * the plan, after the answer but before its score, and after the score but before the next question.
 */
class AdaptiveInterviewOrchestratorTests {

    private final UUID owner = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private final List<InterviewQuestion> questionRows = new ArrayList<>();
    private final List<InterviewAnswer> answerRows = new ArrayList<>();
    private final List<InterviewTurnEvaluation> turnRows = new ArrayList<>();
    private final List<InterviewPolicyDecision> decisionRows = new ArrayList<>();
    private final List<InterviewCompetencyAssessment> assessmentRows = new ArrayList<>();
    private final List<InterviewEvaluation> evaluationRows = new ArrayList<>();

    private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final InterviewAnswerRepository answers = mock(InterviewAnswerRepository.class);
    private final InterviewTurnEvaluationRepository turnEvaluations = mock(InterviewTurnEvaluationRepository.class);
    private final InterviewPolicyDecisionRepository decisions = mock(InterviewPolicyDecisionRepository.class);
    private final InterviewCompetencyAssessmentRepository assessments =
            mock(InterviewCompetencyAssessmentRepository.class);
    private final InterviewEvaluationRepository evaluations = mock(InterviewEvaluationRepository.class);
    private final InterviewPlanner planner = mock(InterviewPlanner.class);
    private final AdaptiveQuestionService questionService = mock(AdaptiveQuestionService.class);
    private final AnswerEvaluator evaluator = mock(AnswerEvaluator.class);
    private final AdaptiveReportGenerator reports = mock(AdaptiveReportGenerator.class);
    private final OpportunityService opportunities = mock(OpportunityService.class);
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final InterviewMetrics metrics = new InterviewMetrics(registry);

    private InterviewSession session;
    private AdaptiveInterviewOrchestrator orchestrator;

    @BeforeEach
    void wireRows() {
        session = InterviewSession.createAdaptive(owner, null, "Acme", "后端工程师", null, "MID", null, false, "JD");
        setSessionId(session, sessionId);
        orchestrator = new AdaptiveInterviewOrchestrator(sessions, questions, answers, turnEvaluations, decisions,
                assessments, evaluations, planner, questionService, evaluator, new AdaptivePolicyEngine(),
                new CompetencyProfileManager(), reports, opportunities, metrics,
                mock(PlatformTransactionManager.class));

        when(sessions.findByIdAndUserId(sessionId, owner)).thenAnswer(call -> Optional.of(session));
        when(sessions.findOwnedForUpdate(sessionId, owner)).thenAnswer(call -> Optional.of(session));
        when(sessions.save(any(InterviewSession.class))).thenAnswer(call -> call.getArgument(0));

        when(questions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(owner, sessionId))
                .thenAnswer(call -> questionRows.stream()
                        .sorted(Comparator.comparingInt(InterviewQuestion::getSequenceNumber)).toList());
        when(questions.findByIdAndUserIdAndInterviewSessionId(any(), eq(owner), eq(sessionId)))
                .thenAnswer(call -> questionRows.stream()
                        .filter(row -> row.getId().equals(call.getArgument(0))).findFirst());
        when(questions.save(any(InterviewQuestion.class))).thenAnswer(call -> {
            InterviewQuestion row = call.getArgument(0);
            questionRows.removeIf(existing -> existing.getId().equals(row.getId()));
            questionRows.add(row);
            return row;
        });

        when(answers.findAllByUserIdAndInterviewSessionId(owner, sessionId)).thenAnswer(call -> List.copyOf(answerRows));
        when(answers.findByInterviewQuestionIdAndUserId(any(), eq(owner))).thenAnswer(call -> answerRows.stream()
                .filter(row -> row.getInterviewQuestionId().equals(call.getArgument(0))).findFirst());
        when(answers.save(any(InterviewAnswer.class))).thenAnswer(call -> {
            InterviewAnswer row = call.getArgument(0);
            answerRows.removeIf(existing -> existing.getId().equals(row.getId()));
            answerRows.add(row);
            return row;
        });

        when(turnEvaluations.findAllByUserIdAndInterviewSessionId(owner, sessionId)).thenAnswer(call -> List.copyOf(turnRows));
        when(turnEvaluations.findByInterviewAnswerIdAndUserId(any(), eq(owner))).thenAnswer(call -> turnRows.stream()
                .filter(row -> row.getInterviewAnswerId().equals(call.getArgument(0))).findFirst());
        when(turnEvaluations.save(any(InterviewTurnEvaluation.class))).thenAnswer(call -> {
            InterviewTurnEvaluation row = call.getArgument(0);
            turnRows.add(row);
            return row;
        });

        when(decisions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(owner, sessionId))
                .thenAnswer(call -> decisionRows.stream()
                        .sorted(Comparator.comparingInt(InterviewPolicyDecision::getSequenceNumber)).toList());
        when(decisions.save(any(InterviewPolicyDecision.class))).thenAnswer(call -> {
            InterviewPolicyDecision row = call.getArgument(0);
            decisionRows.add(row);
            return row;
        });

        when(assessments.findAllByUserIdAndInterviewSessionId(owner, sessionId))
                .thenAnswer(call -> List.copyOf(assessmentRows));
        when(assessments.findByInterviewSessionIdAndCompetencyAndUserId(eq(sessionId), any(), eq(owner)))
                .thenAnswer(call -> assessmentRows.stream()
                        .filter(row -> row.getCompetency().equals(call.getArgument(1))).findFirst());
        when(assessments.save(any(InterviewCompetencyAssessment.class))).thenAnswer(call -> {
            InterviewCompetencyAssessment row = call.getArgument(0);
            assessmentRows.removeIf(existing -> existing.getId().equals(row.getId()));
            assessmentRows.add(row);
            return row;
        });

        when(evaluations.findByInterviewSessionIdAndUserId(sessionId, owner))
                .thenAnswer(call -> evaluationRows.stream().findFirst());
        when(evaluations.save(any(InterviewEvaluation.class))).thenAnswer(call -> {
            InterviewEvaluation row = call.getArgument(0);
            evaluationRows.add(row);
            return row;
        });

        when(planner.plan(owner, sessionId)).thenReturn(plan());
        when(planner.require(owner, sessionId)).thenReturn(plan());
        when(questionService.generate(eq(owner), eq(sessionId), any(QuestionTarget.class)))
                .thenAnswer(call -> generated(call.getArgument(2)));
        when(evaluator.evaluate(eq(owner), any(InterviewQuestion.class), anyString()))
                .thenAnswer(call -> scored(3, "0.90"));
        when(reports.generate(eq(owner), any())).thenReturn(new AdaptiveReportGenerator.Report(
                new BigDecimal("72.00"), Map.of("reportKind", "ADAPTIVE", "jobFit", "基本匹配"), "test-model"));
    }

    /** Hibernate assigns nothing in a unit test, so the identity the row would have is set here. */
    private static void setSessionId(InterviewSession target, UUID id) {
        org.springframework.test.util.ReflectionTestUtils.setField(target, "id", id);
    }

    private static InterviewPlanner.CompetencyPlan plan() {
        return new InterviewPlanner.CompetencyPlan(List.of(
                new InterviewPlanner.CompetencyTarget("JVM", 5, 3, "TECHNICAL",
                        List.of("GC 选型", "内存模型"), true, "岗位核心"),
                new InterviewPlanner.CompetencyTarget("Spring", 3, 2, "TECHNICAL",
                        List.of("Bean 生命周期", "事务传播"), false, "岗位常用")),
                "test-model", InterviewPlanner.PROMPT_VERSION);
    }

    private static AdaptiveQuestionService.GeneratedQuestion generated(QuestionTarget target) {
        AdaptiveRubric rubric = AdaptiveRubric.create(target.knowledgePoint(), target.difficulty(),
                List.of("说清定义", "说清机制"), List.of("对比取舍"), List.of("只背定义"));
        return new AdaptiveQuestionService.GeneratedQuestion("请讲讲" + target.knowledgePoint(),
                target.questionType(), rubric.expectedPoints(), target.withRubric(rubric), null);
    }

    private static AnswerEvaluator.Evaluation scored(int score, String confidence) {
        return new AnswerEvaluator.Evaluation(AnswerKind.ANSWER, score, new BigDecimal(confidence),
                List.of(new AnswerEvaluator.PointEvidence("说清定义", "原文片段")),
                score >= 3 ? List.of() : List.of("说清机制"), List.of(),
                score <= 1 ? List.of("说不清机制") : List.of(), "", "test-model", AnswerEvaluator.PROMPT_VERSION);
    }

    private InterviewQuestion latestQuestion() {
        return questionRows.stream().max(Comparator.comparingInt(InterviewQuestion::getSequenceNumber)).orElseThrow();
    }

    private AdaptiveInterviewState ask() {
        return orchestrator.state(owner, sessionId);
    }

    private AdaptiveInterviewState answer(String text) {
        return orchestrator.submitAnswer(owner, sessionId, latestQuestion().getId(), text, 30, null);
    }

    @Test void readingStateOpensTheInterviewWithAQuestionAndItsDecision() {
        var state = ask();

        assertThat(state.currentQuestion()).isNotNull();
        assertThat(state.currentQuestion().difficulty()).isEqualTo(3);
        assertThat(state.currentQuestion().competency()).isEqualTo("JVM");
        assertThat(state.askedCount()).isEqualTo(1);
        assertThat(state.lastDecision().action()).isEqualTo(PolicyAction.OPEN);
        assertThat(questionRows).hasSize(1);
        assertThat(decisionRows).hasSize(1);
        assertThat(decisionRows.get(0).getInterviewQuestionId()).isEqualTo(questionRows.get(0).getId());
    }

    @Test void everyAskedQuestionCarriesTheRubricThatWasFrozenBeforeAsking() {
        ask();

        assertThat(questionRows.get(0).getRubric()).isNotNull();
        assertThat(AdaptiveRubric.fromMap(questionRows.get(0).getRubric()).expectedPoints())
                .containsExactly("说清定义", "说清机制");
    }

    @Test void theCandidateFacingStateNeverCarriesTheScoringKey() {
        ask();

        assertThat(AdaptiveInterviewState.Question.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("rubric", "evaluationCriteria");
    }

    @Test void everyCompetencyStartsUnverifiedRatherThanAssumedFromTheResume() {
        var state = ask();

        assertThat(state.competencies()).hasSize(2)
                .allSatisfy(entry -> assertThat(entry.state()).isEqualTo(CompetencyState.UNVERIFIED));
        assertThat(state.competencies()).extracting(AdaptiveInterviewState.Competency::estimatedLevel)
                .containsOnlyNulls();
        assertThat(state.competencies().get(0).skill()).isEqualTo("JVM");
    }

    @Test void aGoodAnswerRaisesDifficultyAndAsksTheNextQuestion() {
        ask();

        var state = answer("分代回收，复制算法用于新生代");

        assertThat(state.lastDecision().action()).isEqualTo(PolicyAction.RAISE);
        assertThat(state.currentQuestion().difficulty()).isEqualTo(4);
        assertThat(state.currentQuestion().origin()).isEqualTo(QuestionOrigin.FOLLOW_UP);
        assertThat(state.answeredCount()).isEqualTo(1);
        assertThat(turnRows).hasSize(1);
    }

    @Test void aScoredTurnFillsInOnlyTheCompetencyItActuallyTested() {
        ask();
        answer("分代回收");

        assertThat(assessmentRows).filteredOn(row -> row.getCompetency().equals("JVM")).singleElement()
                .satisfies(row -> {
                    assertThat(row.getState()).isEqualTo(CompetencyState.VERIFIED);
                    assertThat(row.getEstimatedLevel()).isEqualTo(3);
                    assertThat(row.getEvidenceCount()).isEqualTo(1);
                });
        assertThat(assessmentRows).filteredOn(row -> row.getCompetency().equals("Spring")).singleElement()
                .satisfies(row -> {
                    assertThat(row.getState()).isEqualTo(CompetencyState.UNVERIFIED);
                    assertThat(row.getEstimatedLevel()).isNull();
                });
    }

    @Test void submittingTheSameAnswerTwiceKeepsOneAnswerAndOneScore() {
        ask();
        UUID questionId = latestQuestion().getId();
        orchestrator.submitAnswer(owner, sessionId, questionId, "分代回收", 30, null);
        int questionsAfterFirst = questionRows.size();

        orchestrator.submitAnswer(owner, sessionId, questionId, "分代回收", 30, null);

        assertThat(answerRows).hasSize(1);
        assertThat(turnRows).hasSize(1);
        assertThat(questionRows).hasSize(questionsAfterFirst);
        verify(evaluator, times(1)).evaluate(eq(owner), any(InterviewQuestion.class), anyString());
    }

    @Test void retryingAnEarlierAnsweredQuestionReturnsTheCurrentTurnWithoutASecondAnswer() {
        ask();
        UUID first = latestQuestion().getId();
        var afterFirst = answer("分代回收");

        var retried = orchestrator.submitAnswer(owner, sessionId, first, "分代回收", 30, null);

        assertThat(answerRows).hasSize(1);
        assertThat(retried.currentQuestion().id()).isEqualTo(afterFirst.currentQuestion().id());
    }

    @Test void aQuestionFromAnotherSessionIsNotFound() {
        ask();

        assertThatThrownBy(() -> orchestrator.submitAnswer(owner, sessionId, UUID.randomUUID(), "答案", 10, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(answerRows).isEmpty();
    }

    @Test void anotherUsersSessionIsNotFound() {
        UUID intruder = UUID.randomUUID();
        when(sessions.findByIdAndUserId(sessionId, intruder)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.state(intruder, sessionId))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(questionService, evaluator, reports);
    }

    @Test void aClassicSessionIsSentBackToTheClassicEntryPoint() {
        var classic = InterviewSession.create(owner, null, InterviewModality.TEXT, null, "后端工程师", null);
        setSessionId(classic, sessionId);
        when(sessions.findByIdAndUserId(sessionId, owner)).thenReturn(Optional.of(classic));

        assertThatThrownBy(() -> orchestrator.state(owner, sessionId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("固定题目");
        verifyNoInteractions(questionService, evaluator, reports);
    }

    @Test void difficultyFeedbackIsStoredOnTheQuestionItWasGivenFor() {
        ask();
        UUID questionId = latestQuestion().getId();

        orchestrator.submitAnswer(owner, sessionId, questionId, "分代回收", 30, DifficultyFeedback.TOO_HARD);

        assertThat(questionRows.get(0).getDifficultyFeedback()).isEqualTo(DifficultyFeedback.TOO_HARD);
        assertThat(latestQuestion().getDifficulty()).isEqualTo(2);
    }

    @Test void anAnswerSavedBeforeItsScoreIsScoredOnTheNextRead() {
        ask();
        answerRows.add(InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30));

        var state = ask();

        assertThat(turnRows).hasSize(1);
        assertThat(state.answeredCount()).isEqualTo(1);
        assertThat(state.currentQuestion().sequenceNumber()).isEqualTo(2);
        verify(evaluator, times(1)).evaluate(eq(owner), any(InterviewQuestion.class), anyString());
    }

    @Test void aScoredTurnWaitingForItsNextQuestionGetsOneWithoutRescoring() {
        ask();
        InterviewQuestion asked = latestQuestion();
        InterviewAnswer saved = InterviewAnswer.create(owner, sessionId, asked.getId(), "分代回收", 30);
        answerRows.add(saved);
        turnRows.add(InterviewTurnEvaluation.createScored(owner, sessionId, asked.getId(), saved.getId(),
                AnswerKind.ANSWER, 3, new BigDecimal("0.90"), scored(3, "0.90").toMap(),
                "test-model", AnswerEvaluator.PROMPT_VERSION));

        var state = ask();

        assertThat(state.currentQuestion().sequenceNumber()).isEqualTo(2);
        assertThat(state.currentQuestion().difficulty()).isEqualTo(4);
        verifyNoInteractions(evaluator);
    }

    @Test void aFailedScoringCallKeepsTheAnswerAndRecordsWhyItFailed() {
        ask();
        answerRows.add(InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30));
        when(evaluator.evaluate(eq(owner), any(InterviewQuestion.class), anyString()))
                .thenThrow(new LlmCallException("LLM_TIMEOUT", "fixture", true, null));

        var state = ask();

        assertThat(answerRows).hasSize(1);
        assertThat(answerRows.get(0).getEvaluationAttempts()).isEqualTo(1);
        assertThat(answerRows.get(0).getEvaluationError()).isEqualTo("LLM_TIMEOUT");
        assertThat(state.evaluationError()).isEqualTo("LLM_TIMEOUT");
        assertThat(turnRows).isEmpty();
        assertThat(state.awaitingEvaluation()).isTrue();
        assertThat(state.readyToFinish()).isFalse();
        assertThat(questionRows).hasSize(1);
    }

    @Test void aRetryableTurnIsScoredAgainOnTheNextReadInsteadOfBeingSkipped() {
        ask();
        answerRows.add(InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30));
        when(evaluator.evaluate(eq(owner), any(InterviewQuestion.class), anyString()))
                .thenThrow(new LlmCallException("LLM_TIMEOUT", "fixture", true, null))
                .thenReturn(scored(3, "0.90"));

        ask();
        var recovered = ask();

        assertThat(turnRows).hasSize(1);
        assertThat(answerRows.get(0).getEvaluationError()).isNull();
        assertThat(recovered.awaitingEvaluation()).isFalse();
        assertThat(recovered.currentQuestion().sequenceNumber()).isEqualTo(2);
        assertThat(recovered.currentQuestion().difficulty()).isEqualTo(4);
    }

    @Test void aTurnThatExhaustsItsRetriesStaysUnscoredAndTheInterviewStillMovesOn() {
        ask();
        InterviewAnswer saved = InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30);
        for (int attempt = 0; attempt < AdaptiveInterviewOrchestrator.MAX_EVALUATION_ATTEMPTS; attempt++) {
            saved.recordEvaluationFailure("LLM_TIMEOUT");
        }
        answerRows.add(saved);

        var state = ask();

        assertThat(turnRows).isEmpty();
        assertThat(state.currentQuestion().sequenceNumber()).isEqualTo(2);
        verifyNoInteractions(evaluator);
    }

    @Test void anUnscoredTurnIsNotEvidenceSoItCannotLowerTheEstimatedLevel() {
        ask();
        InterviewAnswer saved = InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30);
        for (int attempt = 0; attempt < AdaptiveInterviewOrchestrator.MAX_EVALUATION_ATTEMPTS; attempt++) {
            saved.recordEvaluationFailure("LLM_TIMEOUT");
        }
        answerRows.add(saved);

        ask();

        assertThat(assessmentRows).filteredOn(row -> row.getCompetency().equals("JVM")).singleElement()
                .satisfies(row -> {
                    assertThat(row.getEvidenceCount()).isZero();
                    assertThat(row.getEstimatedLevel()).isNull();
                    assertThat(row.getKnowledgeGaps()).isEmpty();
                });
        assertThat(latestQuestion().getDifficulty()).isEqualTo(3);
    }

    @Test void anInvalidModelScoreIsRecordedAsAFailureNotAsAZero() {
        ask();
        answerRows.add(InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30));
        when(evaluator.evaluate(eq(owner), any(InterviewQuestion.class), anyString()))
                .thenThrow(new IllegalStateException("quote 不是答案原文"));

        ask();

        assertThat(turnRows).isEmpty();
        assertThat(answerRows.get(0).getEvaluationError()).isEqualTo("LLM_INVALID_RESPONSE");
    }

    @Test void aCandidateWhoAsksWhatTheQuestionMeansIsClarifiedInsteadOfScored() {
        ask();
        when(evaluator.evaluate(eq(owner), any(InterviewQuestion.class), anyString()))
                .thenReturn(new AnswerEvaluator.Evaluation(AnswerKind.CLARIFICATION, null, null,
                        List.of(), List.of(), List.of(), List.of(), "", "test-model",
                        AnswerEvaluator.PROMPT_VERSION));

        var state = answer("这道题问的是选型还是调优？");

        assertThat(turnRows).hasSize(1);
        assertThat(turnRows.get(0).getAnswerKind()).isEqualTo(AnswerKind.CLARIFICATION);
        assertThat(turnRows.get(0).getEvaluation()).containsEntry("score", null).containsEntry("confidence", null);
        assertThat(state.currentQuestion().origin()).isEqualTo(QuestionOrigin.CLARIFICATION);
        assertThat(state.currentQuestion().difficulty()).isEqualTo(3);
        assertThat(assessmentRows).filteredOn(row -> row.getCompetency().equals("JVM")).singleElement()
                .satisfies(row -> {
                    assertThat(row.getEvidenceCount()).isZero();
                    assertThat(row.getState()).isEqualTo(CompetencyState.UNVERIFIED);
                });
    }

    @Test void aQuestionGeneratedWhileAnotherRequestAlreadyAskedIsDiscarded() {
        ask();
        answerRows.add(InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30));
        AtomicBoolean interleaved = new AtomicBoolean();
        when(questionService.generate(eq(owner), eq(sessionId), any(QuestionTarget.class))).thenAnswer(call -> {
            // Generation holds no lock, so another recovery read can complete its own advance inside it.
            if (interleaved.compareAndSet(false, true)) ask();
            return generated(call.getArgument(2));
        });

        var state = ask();

        assertThat(questionRows).hasSize(2);
        assertThat(decisionRows).hasSize(2);
        assertThat(state.askedCount()).isEqualTo(2);
        assertThat(state.currentQuestion().id()).isEqualTo(latestQuestion().getId());
    }

    @Test void aScorePersistedByAnotherRequestDuringTheModelCallIsNotInsertedTwice() {
        ask();
        InterviewAnswer pending = InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30);
        answerRows.add(pending);
        AtomicBoolean interleaved = new AtomicBoolean();
        when(evaluator.evaluate(eq(owner), any(InterviewQuestion.class), anyString())).thenAnswer(call -> {
            if (interleaved.compareAndSet(false, true)) {
                turnRows.add(InterviewTurnEvaluation.createScored(owner, sessionId,
                        pending.getInterviewQuestionId(), pending.getId(), AnswerKind.ANSWER, 3,
                        new BigDecimal("0.90"), Map.of("answerKind", "ANSWER"), "test-model",
                        AnswerEvaluator.PROMPT_VERSION));
            }
            return scored(3, "0.90");
        });

        ask();

        assertThat(turnRows).hasSize(1);
        var order = inOrder(sessions, turnEvaluations);
        order.verify(sessions).findOwnedForUpdate(sessionId, owner);
        order.verify(turnEvaluations, atLeastOnce()).findByInterviewAnswerIdAndUserId(pending.getId(), owner);
    }

    @Test void questionsThatNeverGotAScoreStillCountTowardTheSessionBudget() {
        for (int sequence = 1; sequence <= AdaptivePolicyEngine.MAX_QUESTIONS; sequence++) {
            QuestionTarget target = new QuestionTarget("JVM", "GC 选型", 3, "TECHNICAL", QuestionOrigin.PLAN, null);
            InterviewQuestion row = questions.save(generated(target).toEntity(owner, sessionId, sequence));
            InterviewAnswer saved = InterviewAnswer.create(owner, sessionId, row.getId(), "分代回收", 30);
            for (int attempt = 0; attempt < AdaptiveInterviewOrchestrator.MAX_EVALUATION_ATTEMPTS; attempt++) {
                saved.recordEvaluationFailure("LLM_TIMEOUT");
            }
            answerRows.add(saved);
        }

        var state = ask();

        assertThat(questionRows).hasSize(AdaptivePolicyEngine.MAX_QUESTIONS);
        assertThat(state.lastDecision().action()).isEqualTo(PolicyAction.TERMINATE);
        assertThat(state.readyToFinish()).isTrue();
        verifyNoInteractions(evaluator, questionService);
    }

    @Test void aSessionInterruptedBeforeItsPlanRegeneratesItOnRead() {
        when(planner.plan(owner, sessionId)).thenReturn(plan());

        var state = ask();

        assertThat(state.currentQuestion()).isNotNull();
        verify(planner, atLeastOnce()).plan(owner, sessionId);
        assertThat(assessmentRows).hasSize(2);
    }

    @Test void aLowConfidenceScoreAsksAnotherAngleInsteadOfLoweringTheLevel() {
        ask();
        when(evaluator.evaluate(eq(owner), any(InterviewQuestion.class), anyString()))
                .thenReturn(scored(1, "0.30"));

        var state = answer("大概是分代吧");

        assertThat(state.currentQuestion().origin()).isEqualTo(QuestionOrigin.ALTERNATE);
        assertThat(state.currentQuestion().difficulty()).isEqualTo(3);
        assertThat(assessmentRows).filteredOn(row -> row.getCompetency().equals("JVM")).singleElement()
                .satisfies(row -> assertThat(row.getEstimatedLevel()).isNull());
    }

    @Test void theDecisionPayloadRecordsTheTargetAndTheTurnCountItRead() {
        ask();
        answer("分代回收");

        assertThat(decisionRows).hasSize(2);
        Map<String, Object> payload = decisionRows.get(1).getDecision();
        assertThat(payload).containsEntry("turnsRead", 1).containsEntry("action", "RAISE");
        assertThat(payload.get("target")).isInstanceOf(Map.class);
        assertThat(String.valueOf(payload.get("target"))).contains(questionRows.get(0).getId().toString());
    }

    @Test void readingStateTwiceDoesNotAskTheSameUnansweredQuestionAgain() {
        ask();

        ask();

        assertThat(questionRows).hasSize(1);
        assertThat(decisionRows).hasSize(1);
        verify(questionService, times(1)).generate(eq(owner), eq(sessionId), any(QuestionTarget.class));
    }

    @Test void exhaustingEveryCompetencyRecordsOneTerminationAndNoQuestion() {
        var exhausted = new InterviewPlanner.CompetencyPlan(List.of(
                new InterviewPlanner.CompetencyTarget("JVM", 5, 3, "TECHNICAL", List.of("GC 选型"), true, "岗位核心")),
                "test-model", InterviewPlanner.PROMPT_VERSION);
        when(planner.plan(owner, sessionId)).thenReturn(exhausted);
        when(planner.require(owner, sessionId)).thenReturn(exhausted);
        when(evaluator.evaluate(eq(owner), any(InterviewQuestion.class), anyString())).thenReturn(scored(1, "0.90"));

        ask();
        for (int turn = 0; turn < 6 && latestQuestion() != null; turn++) {
            answer("不会");
            if (turnRows.size() >= 4) break;
        }
        var state = ask();
        int decisionsAtTermination = decisionRows.size();
        ask();

        assertThat(decisionRows).anySatisfy(row -> assertThat(row.getAction()).isEqualTo(PolicyAction.TERMINATE));
        assertThat(decisionRows).filteredOn(row -> row.getAction() == PolicyAction.TERMINATE).hasSize(1);
        assertThat(decisionRows).hasSize(decisionsAtTermination);
        assertThat(state.readyToFinish()).isTrue();
        assertThat(state.currentQuestion()).isNull();
    }

    @Test void finishingWritesTheReportAndCompletesTheSession() {
        ask();
        answer("分代回收");

        InterviewEvaluation report = orchestrator.finish(owner, sessionId);

        assertThat(report.getOverallScore()).isEqualByComparingTo("72.00");
        assertThat(report.getPromptVersion()).isEqualTo(AdaptiveReportGenerator.PROMPT_VERSION);
        assertThat(session.getStatus()).isEqualTo(InterviewStatus.COMPLETED);
        assertThat(evaluationRows).hasSize(1);
    }

    @Test void finishingTwiceReturnsTheSavedReportWithoutAnotherModelCall() {
        ask();
        answer("分代回收");
        orchestrator.finish(owner, sessionId);

        InterviewEvaluation again = orchestrator.finish(owner, sessionId);

        assertThat(again).isSameAs(evaluationRows.get(0));
        verify(reports, times(1)).generate(eq(owner), any());
    }

    @Test void finishingSettlesAnAnswerThatWasStillWaitingToBeScored() {
        ask();
        answerRows.add(InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30));

        orchestrator.finish(owner, sessionId);

        assertThat(turnRows).hasSize(1);
        verify(evaluator, times(1)).evaluate(eq(owner), any(InterviewQuestion.class), anyString());
    }

    @Test void finishingSettlesPendingScoresWithoutAskingAQuestionNobodyWillAnswer() {
        ask();
        answerRows.add(InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30));
        clearInvocations(questionService);

        orchestrator.finish(owner, sessionId);

        assertThat(turnRows).hasSize(1);
        assertThat(questionRows).hasSize(1);
        verifyNoInteractions(questionService);
    }

    @Test void finishingStillWritesTheReportWhenTheNextQuestionCouldNotBeGenerated() {
        ask();
        answerRows.add(InterviewAnswer.create(owner, sessionId, latestQuestion().getId(), "分代回收", 30));
        when(questionService.generate(eq(owner), eq(sessionId), any(QuestionTarget.class)))
                .thenThrow(new LlmCallException("LLM_TIMEOUT", "fixture", true, null));

        InterviewEvaluation report = orchestrator.finish(owner, sessionId);

        assertThat(report.getOverallScore()).isEqualByComparingTo("72.00");
        assertThat(session.getStatus()).isEqualTo(InterviewStatus.COMPLETED);
    }

    @Test void aSessionCancelledWhileTheReportWasBeingWrittenDoesNotSaveIt() {
        ask();
        answer("分代回收");
        when(reports.generate(eq(owner), any())).thenAnswer(call -> {
            session.cancel();
            return new AdaptiveReportGenerator.Report(new BigDecimal("72.00"), Map.of("reportKind", "ADAPTIVE"),
                    "test-model");
        });

        assertThatThrownBy(() -> orchestrator.finish(owner, sessionId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已取消");
        assertThat(evaluationRows).isEmpty();
    }

    @Test void theReportInputCarriesThePersistedProfileAndTheAnswerTranscript() {
        ask();
        answer("分代回收");

        orchestrator.finish(owner, sessionId);

        var captor = org.mockito.ArgumentCaptor.forClass(AdaptiveReportGenerator.Input.class);
        verify(reports).generate(eq(owner), captor.capture());
        assertThat(captor.getValue().profiles()).hasSize(2);
        assertThat(captor.getValue().transcript()).singleElement()
                .satisfies(entry -> assertThat(entry).containsEntry("answer", "分代回收").containsEntry("score", 3));
    }

    @Test void startingRequiresATargetRoleBeforeSpendingAnyModelCall() {
        assertThatThrownBy(() -> orchestrator.start(owner, null, "Acme", "  ", null, "MID", 30, false, "JD"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("目标岗位");
        verifyNoInteractions(planner, questionService, evaluator, reports);
    }

    @Test void startingAuthorizesTheJobOpportunityBeforeCreatingTheSession() {
        UUID opportunityId = UUID.randomUUID();
        when(opportunities.detail(owner, opportunityId)).thenThrow(new ResourceNotFoundException("岗位不存在"));

        assertThatThrownBy(() -> orchestrator.start(owner, null, "Acme", "后端工程师", opportunityId,
                "MID", 30, false, null)).isInstanceOf(ResourceNotFoundException.class);
        verify(sessions, never()).save(any());
        verifyNoInteractions(planner, questionService);
    }

    @Test void aCompletedSessionIsNotAdvancedAgain() {
        ask();
        answer("分代回收");
        orchestrator.finish(owner, sessionId);
        int askedAfterFinish = questionRows.size();

        var state = ask();

        assertThat(state.status()).isEqualTo(InterviewStatus.COMPLETED);
        assertThat(state.currentQuestion()).isNull();
        assertThat(questionRows).hasSize(askedAfterFinish);
    }

    @Test void aBlankAnswerIsRefusedWithoutConsumingTheQuestion() {
        ask();

        assertThatThrownBy(() -> orchestrator.submitAnswer(owner, sessionId, latestQuestion().getId(), "   ", 10, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("答案不能为空");
        assertThat(answerRows).isEmpty();
        verifyNoInteractions(evaluator);
    }

    @Test void theStateIsRebuildableFromRowsAloneAfterAnInterruption() {
        ask();
        answer("分代回收");
        var before = ask();

        var rebuilt = new AdaptiveInterviewOrchestrator(sessions, questions, answers, turnEvaluations, decisions,
                assessments, evaluations, planner, questionService, evaluator, new AdaptivePolicyEngine(),
                new CompetencyProfileManager(), reports, opportunities, metrics,
                mock(PlatformTransactionManager.class));
        var after = rebuilt.state(owner, sessionId);

        assertThat(after.askedCount()).isEqualTo(before.askedCount());
        assertThat(after.answeredCount()).isEqualTo(before.answeredCount());
        assertThat(after.currentQuestion().id()).isEqualTo(before.currentQuestion().id());
        assertThat(after.competencies()).isEqualTo(before.competencies());
        assertThat(after.lastDecision().action()).isEqualTo(before.lastDecision().action());
    }

    @Test void theOpeningModeIsAdaptiveSoTheClassicListStaysUnchanged() {
        assertThat(session.getMode()).isEqualTo(InterviewMode.ADAPTIVE);
        assertThat(session.getModality()).isEqualTo(InterviewModality.TEXT);
    }

    @Test void aFinishedInterviewIsCountedOnceTogetherWithTheDomainsItActuallyReached() {
        when(sessions.save(any(InterviewSession.class))).thenAnswer(call -> {
            InterviewSession row = call.getArgument(0);
            setSessionId(row, sessionId);
            return row;
        });

        orchestrator.start(owner, null, "Acme", "后端工程师", null, "MID", 30, false, "JD");
        answer("分代回收");
        orchestrator.finish(owner, sessionId);

        assertThat(registry.get("ardor.interview.sessions.started").counter().count()).isEqualTo(1);
        assertThat(registry.get("ardor.interview.sessions.completed").counter().count()).isEqualTo(1);
        assertThat(registry.get("ardor.interview.session.competencies").summary().count()).isEqualTo(1);
        // Domain coverage is measured from the questions actually asked, not from what the plan hoped for.
        assertThat(registry.get("ardor.interview.session.competencies").summary().totalAmount())
                .isEqualTo(questionRows.stream().map(InterviewQuestion::getCompetency).distinct().count());
    }

    @Test void aCandidateDifficultyRatingIsCountedAgainstItsVerdict() {
        ask();

        orchestrator.submitAnswer(owner, sessionId, latestQuestion().getId(), "分代回收", 30,
                DifficultyFeedback.TOO_EASY);

        assertThat(registry.get("ardor.interview.difficulty.feedback").tag("feedback", "TOO_EASY")
                .counter().count()).isEqualTo(1);
        assertThat(registry.find("ardor.interview.difficulty.feedback").tag("feedback", "TOO_HARD")
                .counter()).isNull();
    }
}