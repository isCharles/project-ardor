package com.projectardor.scheduling.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.scheduling.domain.CalendarTask;
import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.domain.CalendarTaskKind;
import com.projectardor.scheduling.domain.CalendarTaskStatus;
import com.projectardor.scheduling.repository.CalendarTaskRepository;
import com.projectardor.common.web.ResourceNotFoundException;

@Service
public class CalendarTaskService {
    @Transactional(readOnly = true)
    public CalendarTaskView detail(UUID userId, UUID taskId) {
        return CalendarTaskView.from(get(userId, taskId));
    }
    private final CalendarTaskRepository repository;
    private final CalendarTaskAgentSlots agentSlots;

    public CalendarTaskService(CalendarTaskRepository repository) {
        this(repository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CalendarTaskService(CalendarTaskRepository repository, CalendarTaskAgentSlots agentSlots) {
        this.repository = repository;
        this.agentSlots = agentSlots;
    }

    @Transactional(readOnly = true)
    public List<CalendarTaskView> listViews(UUID userId, Instant from, Instant to) {
        return list(userId, from, to).stream().map(CalendarTaskView::from).toList();
    }

    @Transactional
    public CalendarTaskView createView(UUID userId, String title, String description, Instant dueAt,
            CalendarTaskPriority priority, CalendarTaskSource source, UUID jobOpportunityId) {
        return flushedView(create(userId, title, description, dueAt, priority, source, jobOpportunityId));
    }

    /**
     * Agent entry point: the same logical action writes at most one task per semantic identity,
     * so a committed write whose response was lost is returned instead of duplicated.
     */
    @Transactional
    public CalendarTaskView createViewOnce(UUID userId, UUID logicalActionId, UUID runId, String title,
            String description, Instant dueAt, CalendarTaskPriority priority, CalendarTaskSource source,
            UUID jobOpportunityId) {
        if (agentSlots == null || logicalActionId == null || runId == null)
            return createView(userId, title, description, dueAt, priority, source, jobOpportunityId);
        String semantic = CalendarTaskAgentSlots.semanticKey(title, dueAt, priority, jobOpportunityId);
        var existing = agentSlots.existing(userId, logicalActionId, runId, semantic);
        if (existing.isPresent()) return detail(userId, existing.get());
        CalendarTaskView created = flushedView(create(userId, title, description, dueAt, priority, source, jobOpportunityId));
        agentSlots.record(userId, logicalActionId, runId, semantic,
                CalendarTaskAgentSlots.callSignature(description), created.id());
        return created;
    }

    @Transactional
    public CalendarTaskView updateView(UUID userId, UUID taskId, String title, String description, Instant dueAt,
            CalendarTaskPriority priority, CalendarTaskStatus status) {
        return flushedView(update(userId, taskId, title, description, dueAt, priority, status));
    }

    @Transactional
    public CalendarTaskView updateGeneralView(UUID userId, UUID taskId, String title, String description, Instant dueAt,
            CalendarTaskPriority priority, CalendarTaskStatus status) {
        return flushedView(updateGeneral(userId, taskId, title, description, dueAt, priority, status));
    }

    private CalendarTaskView flushedView(CalendarTask task) {
        // Preserve lifecycle timestamps previously observed after the entity-returning transaction committed.
        repository.flush();
        return CalendarTaskView.from(task);
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
        return create(userId, title, description, dueAt, priority, source, null);
    }

    @Transactional
    public CalendarTask create(
            UUID userId,
            String title,
            String description,
            Instant dueAt,
            CalendarTaskPriority priority,
            CalendarTaskSource source,
            UUID jobOpportunityId) {
        return repository.save(CalendarTask.create(
                userId,
                requiredTitle(title),
                nullableText(description, 4000),
                dueAt,
                priority == null ? CalendarTaskPriority.MEDIUM : priority,
                source == null ? CalendarTaskSource.MANUAL : source,
                jobOpportunityId));
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
        CalendarTask task = repository.findOwnedForUpdate(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("日程不存在"));
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

    @Transactional
    public CalendarTask updateGeneral(UUID userId, UUID taskId, String title, String description, Instant dueAt,
            CalendarTaskPriority priority, CalendarTaskStatus status) {
        CalendarTask task = repository.findOwnedForUpdate(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("日程不存在"));
        if (task.getTaskKind() != CalendarTaskKind.GENERAL) {
            throw new IllegalArgumentException("该日程由学习、记忆卡或复测模块维护，请修改对应业务对象");
        }
        return update(userId, taskId, title, description, dueAt, priority, status);
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
