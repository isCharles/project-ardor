package com.projectardor.recap.service;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.recap.domain.InterviewReplayAttempt;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;
import com.projectardor.recap.repository.InterviewReplayAttemptRepository;

/** Keeps a replay result and its calendar completion in the same transaction. */
@Service
public class InterviewReplayCommitter {
    private final InterviewRecapQuestionRepository questions;
    private final InterviewReplayAttemptRepository attempts;
    private final InterviewRetestService retests;

    public InterviewReplayCommitter(InterviewRecapQuestionRepository questions,
            InterviewReplayAttemptRepository attempts, InterviewRetestService retests) {
        this.questions = questions;
        this.attempts = attempts;
        this.retests = retests;
    }

    @Transactional
    public InterviewReplayAttempt existing(UUID userId, UUID questionId, UUID requestId,
            String answer, UUID retestTaskId) {
        return attempts.findByUserIdAndRequestId(userId, requestId)
                .map(attempt -> validateAndComplete(userId, questionId, answer, retestTaskId, attempt))
                .orElse(null);
    }

    @Transactional
    public InterviewReplayAttempt save(UUID userId, UUID questionId, InterviewReplayAttempt candidate) {
        // A retry can finish its LLM call while another request is committing. Serialize the
        // final check and write on the owned question, not across a slow model invocation.
        questions.findOwnedForUpdate(questionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("面经问题不存在"));
        InterviewReplayAttempt previous = attempts.findByUserIdAndRequestId(userId, candidate.getRequestId())
                .orElse(null);
        if (previous != null) {
            return validateAndComplete(userId, questionId, candidate.getAnswerText(),
                    candidate.getRetestTaskId(), previous);
        }
        InterviewReplayAttempt saved = attempts.save(candidate);
        if (candidate.getRetestTaskId() != null) {
            retests.complete(userId, questionId, candidate.getRetestTaskId(), candidate.getChallengeText());
        }
        return saved;
    }

    private InterviewReplayAttempt validateAndComplete(UUID userId, UUID questionId, String answer,
            UUID retestTaskId, InterviewReplayAttempt attempt) {
        if (!attempt.getRecapQuestionId().equals(questionId) || !attempt.getAnswerText().equals(answer)) {
            throw new IllegalArgumentException("请求 ID 已用于另一份回答");
        }
        if (!Objects.equals(attempt.getRetestTaskId(), retestTaskId)) {
            throw new IllegalArgumentException("请求 ID 已用于另一项复测或练习");
        }
        if (retestTaskId != null) {
            retests.complete(userId, questionId, retestTaskId, attempt.getChallengeText());
        }
        return attempt;
    }
}
