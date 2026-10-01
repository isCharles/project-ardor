package com.projectardor.recap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.calendar.domain.CalendarTask;
import com.projectardor.calendar.domain.CalendarTaskKind;
import com.projectardor.calendar.domain.CalendarTaskStatus;
import com.projectardor.calendar.repository.CalendarTaskRepository;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.recap.domain.InterviewRecapQuestion;
import com.projectardor.recap.domain.InterviewReplayAttempt;
import com.projectardor.recap.domain.QuestionPerformance;
import com.projectardor.recap.domain.ReplayVerdict;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;
import com.projectardor.recap.repository.InterviewReplayAttemptRepository;

class InterviewRetestServiceTests {
    private final InterviewRecapQuestionRepository questions = mock(InterviewRecapQuestionRepository.class);
    private final InterviewReplayAttemptRepository attempts = mock(InterviewReplayAttemptRepository.class);
    private final CalendarTaskRepository tasks = mock(CalendarTaskRepository.class);
    private final InterviewRetestService service = new InterviewRetestService(questions, attempts, tasks);

    @Test
    void scheduleRequiresOwnedQuestionAndAttemptBeforeWritingTask() {
        UUID userId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        when(questions.findOwnedForUpdate(questionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.schedule(userId, questionId, attemptId, Instant.now().plusSeconds(3600)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(tasks, never()).save(any());

        var question = question(userId);
        when(questions.findOwnedForUpdate(question.getId(), userId)).thenReturn(Optional.of(question));
        when(attempts.findByIdAndUserId(attemptId, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.schedule(userId, question.getId(), attemptId,
                Instant.now().plusSeconds(3600))).isInstanceOf(ResourceNotFoundException.class);
        verify(tasks, never()).save(any());
    }

    @Test
    void repeatedScheduleReturnsTheSameOpenTask() {
        UUID userId = UUID.randomUUID();
        var question = question(userId);
        var attempt = attempt(userId, question.getId());
        Instant dueAt = Instant.now().plusSeconds(3600);
        CalendarTask existing = CalendarTask.createInterviewRetest(userId, question.getId(), attempt.getId(),
                "解释 JVM 堆", dueAt);
        when(questions.findOwnedForUpdate(question.getId(), userId)).thenReturn(Optional.of(question));
        when(attempts.findByIdAndUserId(attempt.getId(), userId)).thenReturn(Optional.of(attempt));
        when(tasks.findFirstByUserIdAndTaskKindAndReplayQuestionIdAndStatusIn(userId,
                CalendarTaskKind.INTERVIEW_RETEST, question.getId(),
                List.of(CalendarTaskStatus.TODO, CalendarTaskStatus.IN_PROGRESS)))
                .thenReturn(Optional.of(existing));

        assertThat(service.schedule(userId, question.getId(), attempt.getId(), dueAt)).isSameAs(existing);
        verify(tasks, never()).save(any());
    }

    @Test
    void newScheduleCreatesOneDatedDeepLinkToTheVariant() {
        UUID userId = UUID.randomUUID();
        var question = question(userId);
        var attempt = attempt(userId, question.getId());
        Instant dueAt = Instant.now().plusSeconds(3600);
        when(questions.findOwnedForUpdate(question.getId(), userId)).thenReturn(Optional.of(question));
        when(attempts.findByIdAndUserId(attempt.getId(), userId)).thenReturn(Optional.of(attempt));
        when(tasks.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarTask scheduled = service.schedule(userId, question.getId(), attempt.getId(), dueAt);

        assertThat(scheduled.getTaskKind()).isEqualTo(CalendarTaskKind.INTERVIEW_RETEST);
        assertThat(scheduled.getDueAt()).isEqualTo(dueAt);
        assertThat(scheduled.getDescription()).isEqualTo("解释 JVM 堆");
        assertThat(scheduled.getActionPath()).isEqualTo(
                "/app/replay?question=" + question.getId() + "&retest=" + scheduled.getId());
    }

    @Test
    void completionRequiresMatchingQuestionAndChallenge() {
        UUID userId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        CalendarTask task = CalendarTask.createInterviewRetest(userId, questionId, UUID.randomUUID(),
                "介绍 JVM", Instant.now().plusSeconds(3600));
        when(tasks.findByIdAndUserId(task.getId(), userId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.complete(userId, UUID.randomUUID(), task.getId(), "介绍 JVM"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.complete(userId, questionId, task.getId(), "另一个题目"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(task.getStatus()).isEqualTo(CalendarTaskStatus.TODO);

        service.complete(userId, questionId, task.getId(), "介绍 JVM");
        service.complete(userId, questionId, task.getId(), "介绍 JVM");
        assertThat(task.getStatus()).isEqualTo(CalendarTaskStatus.COMPLETED);
    }

    private InterviewRecapQuestion question(UUID userId) {
        return InterviewRecapQuestion.create(userId, UUID.randomUUID(), 1, "解释 JVM", "不知道",
                List.of(), "概念欠缺", QuestionPerformance.WEAK, "没有说明内存区域", null, List.of("JVM"));
    }

    private InterviewReplayAttempt attempt(UUID userId, UUID questionId) {
        return InterviewReplayAttempt.create(userId, questionId, UUID.randomUUID(), "回答",
                ReplayVerdict.NEEDS_WORK, "仍需补充", List.of(), List.of(), "解释 JVM 堆", "model");
    }
}
