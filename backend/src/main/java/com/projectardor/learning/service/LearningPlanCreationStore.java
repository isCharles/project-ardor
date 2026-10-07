package com.projectardor.learning.service;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import com.projectardor.calendar.domain.CalendarTaskSource;
import com.projectardor.calendar.service.CalendarTaskService;
import com.projectardor.learning.domain.LearningPlan;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.learning.repository.LearningPlanRepository;

/** Commits a generated lesson and its calendar task together, once per user request. */
@Component
public class LearningPlanCreationStore {
    private final LearningPlanRepository plans;
    private final CalendarTaskService calendar;
    private final JdbcTemplate jdbc;

    public LearningPlanCreationStore(LearningPlanRepository plans, CalendarTaskService calendar, JdbcTemplate jdbc) {
        this.plans = plans;
        this.calendar = calendar;
        this.jdbc = jdbc;
    }

    public LearningPlan existing(UUID userId, UUID requestId, String requestHash) {
        if (requestId == null) return null;
        LearningPlan existing = plans.findByUserIdAndRequestId(userId, requestId)
                .map(plan -> sameRequest(plan, requestHash))
                .orElse(null);
        if (existing != null) return existing;
        if (!jdbc.queryForList("""
                SELECT request_hash FROM deleted_learning_requests WHERE user_id = ? AND request_id = ?
                """, String.class, userId, requestId).isEmpty()) {
            throw new IllegalStateException("这份学习计划已删除，旧请求不能将其恢复");
        }
        return null;
    }

    public void markDeleted(LearningPlan plan) {
        if (plan.getRequestId() == null) return;
        jdbc.update("""
                INSERT INTO deleted_learning_requests (user_id, request_id, request_hash)
                VALUES (?, ?, ?) ON CONFLICT (user_id, request_id) DO NOTHING
                """, plan.getUserId(), plan.getRequestId(), plan.getRequestHash());
    }

    @Transactional
    public LearningPlan save(UUID userId, UUID requestId, LearningPlan candidate) {
        if (requestId != null) {
            // A second caller may finish model generation concurrently. Lock only while writing,
            // then return the first plan instead of creating another calendar task.
            plans.lockRequestSlot(userId, requestId);
            LearningPlan previous = existing(userId, requestId, candidate.getRequestHash());
            if (previous != null) return previous;
        }
        LearningPlan saved = plans.saveAndFlush(candidate);
        calendar.refreshLearningPlan(userId, saved.getId(), saved.getConcept(), saved.getReason(),
                saved.getScheduledAt(), saved.getSourceType() == LearningSourceType.MANUAL
                        ? CalendarTaskSource.MANUAL : CalendarTaskSource.AGENT, false);
        return saved;
    }

    private LearningPlan sameRequest(LearningPlan plan, String requestHash) {
        if (!Objects.equals(plan.getRequestHash(), requestHash)) {
            throw new IllegalArgumentException("请求 ID 已用于另一份学习计划");
        }
        return plan;
    }
}
