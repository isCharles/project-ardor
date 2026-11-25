package com.projectardor.learning.practice.mockinterview.service.adaptive;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.jobsearch.interview.service.OpportunityService;
import com.projectardor.learning.practice.mockinterview.domain.DifficultyFeedback;
import com.projectardor.learning.practice.mockinterview.domain.InterviewAnswer;
import com.projectardor.learning.practice.mockinterview.domain.InterviewCompetencyAssessment;
import com.projectardor.learning.practice.mockinterview.domain.InterviewEvaluation;
import com.projectardor.learning.practice.mockinterview.domain.InterviewMode;
import com.projectardor.learning.practice.mockinterview.domain.InterviewPolicyDecision;
import com.projectardor.learning.practice.mockinterview.domain.InterviewQuestion;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;
import com.projectardor.learning.practice.mockinterview.domain.InterviewTurnEvaluation;
import com.projectardor.learning.practice.mockinterview.domain.PolicyAction;
import com.projectardor.learning.practice.mockinterview.repository.InterviewAnswerRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewCompetencyAssessmentRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewEvaluationRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewPolicyDecisionRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewQuestionRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewSessionRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewTurnEvaluationRepository;
import com.projectardor.learning.practice.mockinterview.service.InterviewMetrics;
import com.projectardor.learning.practice.mockinterview.service.LeetCodeHot100;
import com.projectardor.learning.practice.mockinterview.service.MockInterviewSessionView;
import com.projectardor.platform.model.llm.service.LlmCallException;

/**
 * The one coordinator of an adaptive interview. The model plans, writes questions, scores answers and
 * drafts report prose; everything in between — what to ask next, what the evidence proves, when to
 * stop — is decided by the Java services this class calls.
 *
 * <p>PostgreSQL is the only state store. Every model call happens outside a transaction, and each
 * write re-reads the owned session under a row lock before saving, so a crash between two steps leaves
 * a session the next read can finish: an answer waiting to be scored, or a scored turn waiting for its
 * next question. A question and the decision that produced it are always saved together.
 */
@Service
public class AdaptiveInterviewOrchestrator {

    /** After this many failed scoring calls the turn stays unscored and the interview moves on. */
    public static final int MAX_EVALUATION_ATTEMPTS = 3;

    private final InterviewSessionRepository sessions;
    private final InterviewQuestionRepository questions;
    private final InterviewAnswerRepository answers;
    private final InterviewTurnEvaluationRepository turnEvaluations;
    private final InterviewPolicyDecisionRepository decisions;
    private final InterviewCompetencyAssessmentRepository assessments;
    private final InterviewEvaluationRepository evaluations;
    private final InterviewPlanner planner;
    private final AdaptiveQuestionService questionService;
    private final AnswerEvaluator evaluator;
    private final AdaptivePolicyEngine policy;
    private final CompetencyProfileManager profiles;
    private final AdaptiveReportGenerator reports;
    private final OpportunityService opportunities;
    private final InterviewMetrics metrics;
    private final TransactionTemplate transactions;

    public AdaptiveInterviewOrchestrator(
            InterviewSessionRepository sessions,
            InterviewQuestionRepository questions,
            InterviewAnswerRepository answers,
            InterviewTurnEvaluationRepository turnEvaluations,
            InterviewPolicyDecisionRepository decisions,
            InterviewCompetencyAssessmentRepository assessments,
            InterviewEvaluationRepository evaluations,
            InterviewPlanner planner,
            AdaptiveQuestionService questionService,
            AnswerEvaluator evaluator,
            AdaptivePolicyEngine policy,
            CompetencyProfileManager profiles,
            AdaptiveReportGenerator reports,
            OpportunityService opportunities,
            InterviewMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.questions = questions;
        this.answers = answers;
        this.turnEvaluations = turnEvaluations;
        this.decisions = decisions;
        this.assessments = assessments;
        this.evaluations = evaluations;
        this.planner = planner;
        this.questionService = questionService;
        this.evaluator = evaluator;
        this.policy = policy;
        this.profiles = profiles;
        this.reports = reports;
        this.opportunities = opportunities;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public InterviewSession start(UUID userId, UUID resumeAnalysisId, String targetCompany, String targetRole,
            UUID jobOpportunityId, String seniority, Integer durationMinutes, boolean deepDive, String jobDescription) {
        String role = targetRole == null || targetRole.isBlank()
                ? null : targetRole.strip();
        if (role == null) throw new IllegalArgumentException("目标岗位不能为空");
        String company = targetCompany == null || targetCompany.isBlank() ? null : targetCompany.strip();
        if (jobOpportunityId != null) opportunities.detail(userId, jobOpportunityId);
        InterviewSession session = transactions.execute(status -> sessions.save(InterviewSession.createAdaptive(
                userId, resumeAnalysisId, company, role, jobOpportunityId,
                seniority == null || seniority.isBlank() ? null : seniority.strip(),
                durationMinutes, deepDive,
                jobDescription == null || jobDescription.isBlank() ? null : jobDescription.strip())));
        metrics.recordStart();
        InterviewPlanner.CompetencyPlan plan = planner.plan(userId, session.getId());
        transactions.execute(status -> {
            refreshProfiles(userId, session.getId(), plan, List.of());
            return null;
        });
        advance(userId, session.getId());
        return sessions.findByIdAndUserId(session.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("模拟面试不存在"));
    }

    public List<MockInterviewSessionView> list(UUID userId) {
        return sessions.findAllByUserIdAndModeOrderByCreatedAtDesc(userId, InterviewMode.ADAPTIVE).stream()
                .map(MockInterviewSessionView::from)
                .toList();
    }

    /** Reading the state is also the recovery path: it finishes whatever a previous request left halfway. */
    public AdaptiveInterviewState state(UUID userId, UUID sessionId) {
        requireAdaptive(userId, sessionId);
        advance(userId, sessionId);
        return readState(userId, sessionId);
    }

    public AdaptiveInterviewState submitAnswer(UUID userId, UUID sessionId, UUID questionId, String answerText,
            Integer durationSeconds, DifficultyFeedback feedback) {
        InterviewSession session = requireAdaptive(userId, sessionId);
        if (session.getStatus() != InterviewStatus.IN_PROGRESS) throw new IllegalStateException("当前面试不能提交答案");
        if (answerText == null || answerText.isBlank()) throw new IllegalArgumentException("答案不能为空");
        String normalized = answerText.strip();
        if (durationSeconds != null && durationSeconds < 0) throw new IllegalArgumentException("答题时长不能为负数");
        transactions.execute(status -> {
            InterviewSession current = sessions.findOwnedForUpdate(sessionId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("模拟面试不存在"));
            requireAdaptive(current);
            if (current.getStatus() != InterviewStatus.IN_PROGRESS) throw new IllegalStateException("当前面试不能提交答案");
            InterviewQuestion question = questions.findByIdAndUserIdAndInterviewSessionId(questionId, userId, sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("面试题不存在"));
            // A retried submission is the same request, not a second answer: it re-drives recovery below.
            if (answers.findByInterviewQuestionIdAndUserId(questionId, userId).isPresent()) return null;
            List<InterviewQuestion> asked = asked(userId, sessionId);
            if (asked.isEmpty() || !asked.get(asked.size() - 1).getId().equals(questionId)) {
                throw new IllegalStateException("请按顺序回答当前题目");
            }
            if (feedback != null) {
                question.recordDifficultyFeedback(feedback);
                questions.save(question);
                metrics.recordDifficultyFeedback(feedback);
            }
            answers.save(InterviewAnswer.create(userId, sessionId, questionId, normalized, durationSeconds));
            return null;
        });
        advance(userId, sessionId);
        return readState(userId, sessionId);
    }

    public InterviewEvaluation finish(UUID userId, UUID sessionId) {
        InterviewSession session = requireAdaptive(userId, sessionId);
        var existing = evaluations.findByInterviewSessionIdAndUserId(sessionId, userId);
        if (existing.isPresent()) return existing.get();
        if (session.getStatus() != InterviewStatus.IN_PROGRESS) throw new IllegalStateException("当前面试不能结束");
        InterviewPlanner.CompetencyPlan plan = planner.plan(userId, sessionId);
        // Finishing settles the answers already given. Advancing here would persist a question nobody
        // will answer, and a failure generating it would block the report the candidate asked for.
        scorePendingAnswers(userId, session, plan);
        List<EvaluatedTurn> history = history(userId, sessionId);
        List<CompetencyProfileManager.Snapshot> snapshots = plan.competencies().stream()
                .map(competency -> profiles.summarize(competency.skill(), competency.jdPriority(), history))
                .toList();
        AdaptiveReportGenerator.Report report = reports.generate(userId, new AdaptiveReportGenerator.Input(
                session.getTargetRole(), session.getTargetCompany(), session.getSeniority(), snapshots,
                transcript(userId, sessionId)));
        return transactions.execute(status -> {
            InterviewSession current = sessions.findOwnedForUpdate(sessionId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("模拟面试不存在"));
            var saved = evaluations.findByInterviewSessionIdAndUserId(sessionId, userId);
            if (saved.isPresent()) return saved.get();
            if (current.getStatus() != InterviewStatus.IN_PROGRESS) throw new IllegalStateException("面试已取消，报告未保存");
            InterviewEvaluation evaluation = evaluations.save(InterviewEvaluation.create(userId, sessionId,
                    report.overallScore(), report.content(), report.modelName(), AdaptiveReportGenerator.PROMPT_VERSION));
            current.complete();
            sessions.save(current);
            metrics.recordCompletion((int) asked(userId, sessionId).stream()
                    .map(InterviewQuestion::getCompetency).filter(Objects::nonNull).distinct().count());
            return evaluation;
        });
    }

    private void advance(UUID userId, UUID sessionId) {
        InterviewSession session = requireAdaptive(userId, sessionId);
        if (session.getStatus() != InterviewStatus.IN_PROGRESS) return;
        // plan() is idempotent: a session interrupted before its plan was saved regenerates it here.
        InterviewPlanner.CompetencyPlan plan = planner.plan(userId, sessionId);
        scorePendingAnswers(userId, session, plan);
        askNext(userId, session, plan);
    }

    private void askNext(UUID userId, InterviewSession session, InterviewPlanner.CompetencyPlan plan) {
        List<InterviewQuestion> asked = asked(userId, session.getId());
        Map<UUID, InterviewAnswer> answered = answered(userId, session.getId());
        UUID predecessor = null;
        if (!asked.isEmpty()) {
            InterviewQuestion latest = asked.get(asked.size() - 1);
            predecessor = latest.getId();
            InterviewAnswer latestAnswer = answered.get(predecessor);
            if (latestAnswer == null) return;
            // Deciding around a turn that is still retrying would ask the next question without its
            // evidence, and that question cannot be taken back once the score finally lands.
            if (stillRetryable(userId, latestAnswer)) return;
        }
        List<EvaluatedTurn> history = history(userId, session.getId());
        AdaptivePolicyEngine.Decision decision = policy.decide(new AdaptivePolicyEngine.Context(
                plan.competencies(), history, asked.size(), session.isDeepDive(), session.getDurationMinutes(),
                session.getStartedAt(), Instant.now()));
        if (decision.action() == PolicyAction.TERMINATE) {
            recordDecision(userId, session, decision, history, null, predecessor);
            return;
        }
        AdaptiveQuestionService.GeneratedQuestion generated =
                questionService.generate(userId, session.getId(), decision.target());
        recordDecision(userId, session, decision, history, generated, predecessor);
    }

    private boolean stillRetryable(UUID userId, InterviewAnswer answer) {
        return answer.getEvaluationAttempts() < MAX_EVALUATION_ATTEMPTS
                && turnEvaluations.findByInterviewAnswerIdAndUserId(answer.getId(), userId).isEmpty();
    }

    private void scorePendingAnswers(UUID userId, InterviewSession session, InterviewPlanner.CompetencyPlan plan) {
        Map<UUID, InterviewQuestion> byId = asked(userId, session.getId()).stream()
                .collect(Collectors.toMap(InterviewQuestion::getId, Function.identity()));
        Set<UUID> scored = turnEvaluations.findAllByUserIdAndInterviewSessionId(userId, session.getId()).stream()
                .map(InterviewTurnEvaluation::getInterviewAnswerId)
                .collect(Collectors.toSet());
        for (InterviewAnswer answer : answers.findAllByUserIdAndInterviewSessionId(userId, session.getId())) {
            if (scored.contains(answer.getId()) || answer.getEvaluationAttempts() >= MAX_EVALUATION_ATTEMPTS) continue;
            InterviewQuestion question = byId.get(answer.getInterviewQuestionId());
            if (question == null) continue;
            try {
                AnswerEvaluator.Evaluation evaluation = evaluator.evaluate(userId, question, answer.getAnswerText());
                persistEvaluation(userId, session, plan, question, answer, evaluation);
            } catch (LlmCallException | IllegalStateException failure) {
                recordEvaluationFailure(userId, session, answer, failure);
            }
        }
    }

    private void persistEvaluation(UUID userId, InterviewSession session, InterviewPlanner.CompetencyPlan plan,
            InterviewQuestion question, InterviewAnswer answer, AnswerEvaluator.Evaluation evaluation) {
        transactions.execute(status -> {
            // Serialize on the session row before the existence check: two recoveries scoring the same
            // answer would otherwise both insert and let the unique constraint fail a valid request.
            InterviewSession locked = sessions.findOwnedForUpdate(session.getId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("模拟面试不存在"));
            if (locked.getStatus() != InterviewStatus.IN_PROGRESS) return null;
            if (turnEvaluations.findByInterviewAnswerIdAndUserId(answer.getId(), userId).isPresent()) return null;
            InterviewAnswer current = answers.findAllByUserIdAndInterviewSessionId(userId, session.getId()).stream()
                    .filter(item -> item.getId().equals(answer.getId()))
                    .findFirst().orElse(null);
            if (current == null) return null;
            turnEvaluations.save(evaluation.isClarification()
                    ? InterviewTurnEvaluation.createClarification(userId, session.getId(), question.getId(),
                            answer.getId(), evaluation.toMap(), evaluation.modelName(), evaluation.promptVersion())
                    : InterviewTurnEvaluation.createScored(userId, session.getId(), question.getId(), answer.getId(),
                            evaluation.kind(), evaluation.score(), evaluation.confidence(), evaluation.toMap(),
                            evaluation.modelName(), evaluation.promptVersion()));
            current.clearEvaluationFailure();
            answers.save(current);
            refreshProfiles(userId, session.getId(), plan, history(userId, session.getId()));
            return null;
        });
    }

    private void recordEvaluationFailure(UUID userId, InterviewSession session, InterviewAnswer answer,
            RuntimeException failure) {
        String code = failure instanceof LlmCallException call && call.getCode() != null
                ? call.getCode() : "LLM_INVALID_RESPONSE";
        String bounded = code.length() <= 64 ? code : code.substring(0, 64);
        transactions.execute(status -> {
            answers.findAllByUserIdAndInterviewSessionId(userId, session.getId()).stream()
                    .filter(item -> item.getId().equals(answer.getId()))
                    .findFirst()
                    .ifPresent(current -> {
                        current.recordEvaluationFailure(bounded);
                        answers.save(current);
                    });
            return null;
        });
    }

    /** The question and the decision that produced it are one write, so a crash cannot orphan either. */
    private void recordDecision(UUID userId, InterviewSession session, AdaptivePolicyEngine.Decision decision,
            List<EvaluatedTurn> history, AdaptiveQuestionService.GeneratedQuestion generated, UUID predecessor) {
        transactions.execute(status -> {
            InterviewSession current = sessions.findOwnedForUpdate(session.getId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("模拟面试不存在"));
            if (current.getStatus() != InterviewStatus.IN_PROGRESS) return null;
            List<InterviewPolicyDecision> existing =
                    decisions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(userId, session.getId());
            if (generated == null && !existing.isEmpty()
                    && existing.get(existing.size() - 1).getAction() == PolicyAction.TERMINATE) {
                return null;
            }
            List<InterviewQuestion> asked = asked(userId, session.getId());
            UUID latest = asked.isEmpty() ? null : asked.get(asked.size() - 1).getId();
            // Generation ran without a lock. If another request already asked the next question, this
            // one came from stale history and would skip the question the candidate is looking at.
            if (!Objects.equals(latest, predecessor)) return null;
            int sequence = existing.size() + 1;
            UUID questionId = null;
            if (generated != null) {
                questionId = questions.save(generated.toEntity(userId, session.getId(), sequence)).getId();
                refreshProfiles(userId, session.getId(), planner.require(userId, session.getId()), history);
            }
            decisions.save(InterviewPolicyDecision.create(userId, session.getId(), sequence, questionId,
                    decision.action(), decision.reason(), payload(decision, history)));
            return null;
        });
    }

    private void refreshProfiles(UUID userId, UUID sessionId, InterviewPlanner.CompetencyPlan plan,
            List<EvaluatedTurn> history) {
        for (InterviewPlanner.CompetencyTarget competency : plan.competencies()) {
            CompetencyProfileManager.Snapshot snapshot =
                    profiles.summarize(competency.skill(), competency.jdPriority(), history);
            InterviewCompetencyAssessment assessment = assessments
                    .findByInterviewSessionIdAndCompetencyAndUserId(sessionId, competency.skill(), userId)
                    .orElseGet(() -> InterviewCompetencyAssessment.create(
                            userId, sessionId, competency.skill(), competency.jdPriority()));
            assessment.recordEvidence(snapshot.estimatedLevel(), snapshot.confidence(), snapshot.evidenceCount(),
                    snapshot.state(), snapshot.strengths(), snapshot.knowledgeGaps());
            assessments.save(assessment);
        }
    }

    private AdaptiveInterviewState readState(UUID userId, UUID sessionId) {
        InterviewSession session = requireAdaptive(userId, sessionId);
        List<InterviewQuestion> asked = asked(userId, sessionId);
        Map<UUID, InterviewAnswer> answered = answered(userId, sessionId);
        InterviewQuestion current = null;
        String evaluationError = null;
        boolean awaitingEvaluation = false;
        if (!asked.isEmpty()) {
            InterviewQuestion latest = asked.get(asked.size() - 1);
            InterviewAnswer latestAnswer = answered.get(latest.getId());
            if (latestAnswer == null && session.getStatus() == InterviewStatus.IN_PROGRESS) current = latest;
            if (latestAnswer != null) {
                evaluationError = latestAnswer.getEvaluationError();
                awaitingEvaluation = session.getStatus() == InterviewStatus.IN_PROGRESS
                        && stillRetryable(userId, latestAnswer);
            }
        }
        List<AdaptiveInterviewState.Competency> competencies =
                assessments.findAllByUserIdAndInterviewSessionId(userId, sessionId).stream()
                        .sorted(Comparator.comparingInt(InterviewCompetencyAssessment::getJdPriority).reversed()
                                .thenComparing(InterviewCompetencyAssessment::getCompetency))
                        .map(assessment -> new AdaptiveInterviewState.Competency(assessment.getCompetency(),
                                assessment.getJdPriority(), assessment.getEstimatedLevel(), assessment.getConfidence(),
                                assessment.getEvidenceCount(), assessment.getState(), assessment.getStrengths(),
                                assessment.getKnowledgeGaps()))
                        .toList();
        List<InterviewPolicyDecision> decisionRows =
                decisions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(userId, sessionId);
        InterviewPolicyDecision last = decisionRows.isEmpty() ? null : decisionRows.get(decisionRows.size() - 1);
        return new AdaptiveInterviewState(sessionId, session.getStatus(), session.getTargetRole(),
                session.getTargetCompany(), session.getSeniority(), session.getDurationMinutes(), session.isDeepDive(),
                session.getStartedAt(), asked.size(), answered.size(),
                session.getStatus() == InterviewStatus.IN_PROGRESS && current == null && !asked.isEmpty()
                        && !awaitingEvaluation,
                awaitingEvaluation, question(current), evaluationError, competencies,
                last == null ? null : new AdaptiveInterviewState.Decision(
                        last.getAction(), last.getReason(), last.getCreatedAt()));
    }

    private List<EvaluatedTurn> history(UUID userId, UUID sessionId) {
        Map<UUID, InterviewTurnEvaluation> scored =
                turnEvaluations.findAllByUserIdAndInterviewSessionId(userId, sessionId).stream()
                        .collect(Collectors.toMap(InterviewTurnEvaluation::getInterviewQuestionId, Function.identity()));
        List<EvaluatedTurn> turns = new ArrayList<>();
        for (InterviewQuestion question : asked(userId, sessionId)) {
            InterviewTurnEvaluation evaluation = scored.get(question.getId());
            // An answer that could not be scored is not evidence and must not drive the next decision.
            if (evaluation == null) continue;
            Map<String, Object> payload = evaluation.getEvaluation();
            turns.add(new EvaluatedTurn(question.getCompetency(), question.getKnowledgePoint(),
                    question.getDifficulty(), question.getOrigin(), evaluation.getAnswerKind(), evaluation.getScore(),
                    evaluation.getConfidence(), coveredPoints(payload), strings(payload.get("missingPoints")),
                    strings(payload.get("knowledgeGaps")), question.getDifficultyFeedback(), question.getId()));
        }
        return turns;
    }

    private List<Map<String, Object>> transcript(UUID userId, UUID sessionId) {
        Map<UUID, InterviewAnswer> answered = answered(userId, sessionId);
        Map<UUID, InterviewTurnEvaluation> scored =
                turnEvaluations.findAllByUserIdAndInterviewSessionId(userId, sessionId).stream()
                        .collect(Collectors.toMap(InterviewTurnEvaluation::getInterviewQuestionId, Function.identity()));
        List<Map<String, Object>> transcript = new ArrayList<>();
        for (InterviewQuestion question : asked(userId, sessionId)) {
            InterviewAnswer answer = answered.get(question.getId());
            if (answer == null) continue;
            InterviewTurnEvaluation evaluation = scored.get(question.getId());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sequence", question.getSequenceNumber());
            entry.put("competency", question.getCompetency());
            entry.put("knowledgePoint", question.getKnowledgePoint());
            entry.put("difficulty", question.getDifficulty());
            entry.put("question", question.getQuestionText());
            entry.put("answer", answer.getAnswerText());
            entry.put("score", evaluation == null ? null : evaluation.getScore());
            entry.put("answerKind", evaluation == null ? "UNSCORED" : evaluation.getAnswerKind().name());
            entry.put("missingPoints", evaluation == null ? List.of() : strings(evaluation.getEvaluation().get("missingPoints")));
            entry.put("knowledgeGaps", evaluation == null ? List.of() : strings(evaluation.getEvaluation().get("knowledgeGaps")));
            transcript.add(entry);
        }
        return transcript;
    }

    private static Map<String, Object> payload(AdaptivePolicyEngine.Decision decision, List<EvaluatedTurn> history) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", decision.action().name());
        payload.put("reason", decision.reason());
        payload.put("turnsRead", history.size());
        if (decision.target() != null) {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("competency", decision.target().competency());
            target.put("knowledgePoint", decision.target().knowledgePoint());
            target.put("difficulty", decision.target().difficulty());
            target.put("questionType", decision.target().questionType());
            target.put("origin", decision.target().origin().name());
            target.put("parentQuestionId", decision.target().parentQuestionId());
            payload.put("target", target);
        }
        return payload;
    }

    private static AdaptiveInterviewState.Question question(InterviewQuestion question) {
        if (question == null) return null;
        AdaptiveInterviewState.LeetCodeProblem problem = LeetCodeHot100.find(question.getLeetcodeSlug())
                .map(item -> new AdaptiveInterviewState.LeetCodeProblem(
                        item.id(), item.slug(), item.titleEn(), item.titleZh()))
                .orElse(null);
        return new AdaptiveInterviewState.Question(question.getId(), question.getSequenceNumber(),
                question.getQuestionText(), question.getQuestionType(), question.getCompetency(),
                question.getKnowledgePoint(), question.getDifficulty(), question.getOrigin(), problem);
    }

    private static List<String> coveredPoints(Map<String, Object> evaluation) {
        if (!(evaluation.get("coveredPoints") instanceof List<?> items)) return List.of();
        List<String> points = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> entry && entry.get("point") != null) {
                points.add(String.valueOf(entry.get("point")));
            }
        }
        return points;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> items)) return List.of();
        List<String> values = new ArrayList<>();
        for (Object item : items) {
            if (item != null) values.add(String.valueOf(item));
        }
        return values;
    }

    private List<InterviewQuestion> asked(UUID userId, UUID sessionId) {
        return questions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(userId, sessionId);
    }

    private Map<UUID, InterviewAnswer> answered(UUID userId, UUID sessionId) {
        return answers.findAllByUserIdAndInterviewSessionId(userId, sessionId).stream()
                .collect(Collectors.toMap(InterviewAnswer::getInterviewQuestionId, Function.identity()));
    }

    private InterviewSession requireAdaptive(UUID userId, UUID sessionId) {
        InterviewSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("模拟面试不存在"));
        requireAdaptive(session);
        return session;
    }

    private static void requireAdaptive(InterviewSession session) {
        if (session.getMode() != InterviewMode.ADAPTIVE) {
            throw new IllegalStateException("固定题目面试请使用固定题目面试接口");
        }
    }
}