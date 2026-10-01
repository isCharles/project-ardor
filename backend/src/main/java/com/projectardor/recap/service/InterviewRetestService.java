package com.projectardor.recap.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.calendar.domain.CalendarTask;
import com.projectardor.calendar.domain.CalendarTaskKind;
import com.projectardor.calendar.domain.CalendarTaskStatus;
import com.projectardor.calendar.repository.CalendarTaskRepository;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;
import com.projectardor.recap.repository.InterviewReplayAttemptRepository;

@Service
public class InterviewRetestService {
    private static final List<CalendarTaskStatus> OPEN_STATUSES =
            List.of(CalendarTaskStatus.TODO, CalendarTaskStatus.IN_PROGRESS);

    private final InterviewRecapQuestionRepository questions;
    private final InterviewReplayAttemptRepository attempts;
    private final CalendarTaskRepository tasks;

    public InterviewRetestService(InterviewRecapQuestionRepository questions,
            InterviewReplayAttemptRepository attempts, CalendarTaskRepository tasks) {
        this.questions = questions;
        this.attempts = attempts;
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public Optional<CalendarTask> active(UUID userId, UUID questionId) {
        questions.findByIdAndUserId(questionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("面经问题不存在"));
        return activeTask(userId, questionId);
    }

    @Transactional
    public CalendarTask schedule(UUID userId, UUID questionId, UUID attemptId, Instant dueAt) {
        Instant now = Instant.now();
        if (dueAt == null || !dueAt.isAfter(now)
                || dueAt.isAfter(now.plus(90, ChronoUnit.DAYS))) {
            throw new IllegalArgumentException("复测时间须在未来 90 天内");
        }
        // Lock the owned question so simultaneous clicks cannot schedule twice.
        questions.findOwnedForUpdate(questionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("面经问题不存在"));
        var attempt = attempts.findByIdAndUserId(attemptId, userId)
                .filter(item -> item.getRecapQuestionId().equals(questionId))
                .orElseThrow(() -> new ResourceNotFoundException("面试回放不存在"));
        if (attempt.getNextChallenge() == null || attempt.getNextChallenge().isBlank()) {
            throw new IllegalArgumentException("这次回放没有可安排的追问");
        }
        return activeTask(userId, questionId).orElseGet(() -> tasks.save(
                CalendarTask.createInterviewRetest(userId, questionId, attemptId,
                        attempt.getNextChallenge().strip(), dueAt)));
    }

    @Transactional(readOnly = true)
    public String challenge(UUID userId, UUID questionId, UUID taskId) {
        return openTask(userId, questionId, taskId).getDescription();
    }

    @Transactional
    public void complete(UUID userId, UUID questionId, UUID taskId, String answeredChallenge) {
        CalendarTask task = tasks.findByIdAndUserId(taskId, userId)
                .filter(item -> item.getTaskKind() == CalendarTaskKind.INTERVIEW_RETEST)
                .filter(item -> questionId.equals(item.getReplayQuestionId()))
                .orElseThrow(() -> new ResourceNotFoundException("面试复测不存在"));
        if (answeredChallenge == null || !answeredChallenge.equals(task.getDescription())) {
            throw new IllegalArgumentException("这份回答不属于该复测任务");
        }
        if (task.getStatus() == CalendarTaskStatus.COMPLETED) return;
        if (!OPEN_STATUSES.contains(task.getStatus())) {
            throw new IllegalArgumentException("这项复测已取消");
        }
        task.completeInterviewRetest();
    }

    private CalendarTask openTask(UUID userId, UUID questionId, UUID taskId) {
        return tasks.findByIdAndUserId(taskId, userId)
                .filter(item -> item.getTaskKind() == CalendarTaskKind.INTERVIEW_RETEST)
                .filter(item -> questionId.equals(item.getReplayQuestionId()))
                .filter(item -> OPEN_STATUSES.contains(item.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException("待复测任务不存在"));
    }

    private Optional<CalendarTask> activeTask(UUID userId, UUID questionId) {
        return tasks.findFirstByUserIdAndTaskKindAndReplayQuestionIdAndStatusIn(
                userId, CalendarTaskKind.INTERVIEW_RETEST, questionId, OPEN_STATUSES);
    }
}
