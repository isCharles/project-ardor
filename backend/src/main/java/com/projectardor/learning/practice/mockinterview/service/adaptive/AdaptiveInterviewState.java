package com.projectardor.learning.practice.mockinterview.service.adaptive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectardor.learning.practice.mockinterview.domain.CompetencyState;
import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;
import com.projectardor.learning.practice.mockinterview.domain.PolicyAction;
import com.projectardor.learning.practice.mockinterview.domain.QuestionOrigin;

/**
 * What the candidate's screen needs. The rubric stays out: it is the scoring key, written before the
 * question was asked, and showing it would turn the interview into reading comprehension.
 */
public record AdaptiveInterviewState(UUID sessionId, InterviewStatus status, String targetRole, String targetCompany,
        String seniority, Integer durationMinutes, boolean deepDive, Instant startedAt, int askedCount,
        int answeredCount, boolean readyToFinish, boolean awaitingEvaluation, Question currentQuestion,
        String evaluationError, List<Competency> competencies, Decision lastDecision) {

    public AdaptiveInterviewState {
        competencies = List.copyOf(competencies);
    }

    public record Question(UUID id, int sequenceNumber, String questionText, String questionType, String competency,
            String knowledgePoint, Integer difficulty, QuestionOrigin origin, LeetCodeProblem leetcode) {}

    public record LeetCodeProblem(int id, String slug, String titleEn, String titleZh) {}

    public record Competency(String skill, int jdPriority, Integer estimatedLevel, BigDecimal confidence,
            int evidenceCount, CompetencyState state, List<Map<String, Object>> strengths,
            List<Map<String, Object>> knowledgeGaps) {
        public Competency {
            strengths = List.copyOf(strengths);
            knowledgeGaps = List.copyOf(knowledgeGaps);
        }
    }

    public record Decision(PolicyAction action, String reason, Instant createdAt) {}
}