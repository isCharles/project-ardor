package com.projectardor.learning.practice.mockinterview.web;

import java.util.UUID;

import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;
import com.projectardor.learning.practice.mockinterview.service.InterviewService.InterviewProgress;

public record InterviewProgressResponse(
        UUID sessionId,
        InterviewStatus status,
        int answeredCount,
        int totalQuestions,
        boolean readyToFinish,
        InterviewQuestionResponse nextQuestion) {

    public static InterviewProgressResponse from(InterviewProgress progress) {
        return new InterviewProgressResponse(
                progress.session().getId(), progress.session().getStatus(),
                progress.answeredCount(), progress.totalQuestions(),
                progress.session().getStatus() == InterviewStatus.IN_PROGRESS
                        && progress.nextQuestion() == null
                        && progress.totalQuestions() > 0,
                InterviewQuestionResponse.from(progress.nextQuestion()));
    }
}
