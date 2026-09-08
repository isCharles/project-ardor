package com.projectardor.calendar.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.calendar.domain.CalendarTask;
import com.projectardor.calendar.domain.CalendarTaskPriority;
import com.projectardor.calendar.domain.CalendarTaskSource;
import com.projectardor.calendar.domain.CalendarTaskKind;
import com.projectardor.calendar.domain.CalendarTaskStatus;
import com.projectardor.calendar.repository.CalendarTaskRepository;
import com.projectardor.common.web.ResourceNotFoundException;

@Service
public class CalendarTaskService {
    private final CalendarTaskRepository repository;

    public CalendarTaskService(CalendarTaskRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<CalendarTask> list(UUID userId, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("开始时间必须早于结束时间");
        }
        return repository.findAllByUserIdOrderByDueAtAscCreatedAtDesc(userId).stream()
                .filter(task -> inRange(task.getDueAt(), from, to))
                .toList();
    }

    @Transactional
    public CalendarTask create(
            UUID userId,
            String title,
            String description,
            Instant dueAt,
            CalendarTaskPriority priority,
            CalendarTaskSource source) {
        return repository.save(CalendarTask.create(
                userId,
                requiredTitle(title),
                nullableText(description, 4000),
                dueAt,
                priority == null ? CalendarTaskPriority.MEDIUM : priority,
                source == null ? CalendarTaskSource.MANUAL : source));
    }

    @Transactional
    public CalendarTask refreshMemoryCardReview(
            UUID userId, LocalDate reviewDate, int cardCount, Instant dueAt) {
        var existing = repository.findByUserIdAndTaskKindAndReviewDate(
                userId, CalendarTaskKind.MEMORY_REVIEW, reviewDate);
        if (cardCount <= 0) {
            existing.ifPresent(repository::delete);
            return null;
        }
        CalendarTask task = existing.orElseGet(() ->
                CalendarTask.createMemoryReview(userId, reviewDate, cardCount, dueAt));
        task.scheduleMemoryCardReview(cardCount, dueAt);
        return repository.save(task);
    }

    @Transactional
    public CalendarTask refreshLearningPlan(
            UUID userId, UUID learningPlanId, String concept, String reason,
            Instant dueAt, CalendarTaskSource source, boolean completed) {
        CalendarTask task = repository.findByLearningPlanIdAndUserId(learningPlanId, userId)
                .orElseGet(() -> CalendarTask.createLearning(userId, learningPlanId, concept, reason, dueAt, source));
        task.scheduleLearning(concept, reason, dueAt, completed);
        return repository.save(task);
    }

    @Transactional
    public CalendarTask update(
            UUID userId,
            UUID taskId,
            String title,
            String description,
            Instant dueAt,
            CalendarTaskPriority priority,
            CalendarTaskStatus status) {
        CalendarTask task = get(userId, taskId);
        task.update(
                requiredTitle(title),
                nullableText(description, 4000),
                dueAt,
                priority == null ? task.getPriority() : priority,
                status == null ? task.getStatus() : status);
        return task;
    }

    @Transactional
    public void delete(UUID userId, UUID taskId) {
        repository.delete(get(userId, taskId));
    }

    @Transactional(readOnly = true)
    public CalendarTask get(UUID userId, UUID taskId) {
        return repository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("待办不存在"));
    }

    private boolean inRange(Instant dueAt, Instant from, Instant to) {
        if (from == null && to == null) return true;
        if (dueAt == null) return false;
        return (from == null || !dueAt.isBefore(from)) && (to == null || dueAt.isBefore(to));
    }

    private String requiredTitle(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("待办标题不能为空");
        String normalized = value.strip();
        if (normalized.length() > 240) throw new IllegalArgumentException("待办标题不能超过 240 个字符");
        return normalized;
    }

    private String nullableText(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("待办说明过长");
        return normalized;
    }

}
