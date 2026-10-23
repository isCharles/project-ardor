package com.projectardor.learning.practice.mockinterview.web;

import java.util.UUID;

import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;
import com.projectardor.learning.practice.mockinterview.service.InterviewService.InterviewProgress;
import com.projectardor.learning.practice.mockinterview.service.MockInterviewProgressView;

public record InterviewProgressResponse(
        UUID sessionId,
        InterviewStatus status,
        int answeredCount,
        int totalQuestions,
        boolean readyToFinish,
        InterviewQuestionResponse nextQuestion) {

    public static InterviewProgressResponse from(InterviewProgress progress) {
        return fromView(MockInterviewProgressView.from(progress));
    }

    public static InterviewProgressResponse fromView(MockInterviewProgressView progress) {
        return new InterviewProgressResponse(
                progress.sessionId(), progress.status(),
                progress.answeredCount(), progress.totalQuestions(),
                progress.readyToFinish(), InterviewQuestionResponse.fromView(progress.nextQuestion()));
    }
}
