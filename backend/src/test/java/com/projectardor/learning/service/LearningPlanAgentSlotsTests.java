package com.projectardor.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.projectardor.learning.domain.LearningSourceType;

class LearningPlanAgentSlotsTests {
    private static final Instant FIRST = Instant.parse("2026-10-08T01:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-10-09T01:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void reorderedReplacementReusesTheMatchingPersistedSlot() {
        UUID user = UUID.randomUUID();
        UUID logical = UUID.randomUUID();
        UUID oldRun = UUID.randomUUID();
        UUID newRun = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(user), eq(logical), anyString()))
                .thenReturn(List.of(
                        new LearningPlanAgentSlots.Slot(firstId, LearningPlanAgentSlots.signature("原因一", FIRST), oldRun),
                        new LearningPlanAgentSlots.Slot(secondId, LearningPlanAgentSlots.signature("原因二", SECOND), oldRun)));

        assertThat(new LearningPlanAgentSlots(jdbc).resolve(user, logical, newRun, "JVM", "原因二",
                LearningSourceType.AGENT, null, SECOND)).isEqualTo(secondId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void changedArgumentsOnASecondAttemptReuseTheOnlyPersistedSlot() {
        UUID user = UUID.randomUUID();
        UUID logical = UUID.randomUUID();
        UUID firstAttempt = UUID.randomUUID();
        UUID retryAttempt = UUID.randomUUID();
        UUID existingPlan = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(user), eq(logical), anyString()))
                .thenReturn(List.of(new LearningPlanAgentSlots.Slot(existingPlan,
                        LearningPlanAgentSlots.signature("原来的理由", FIRST), firstAttempt)));

        assertThat(new LearningPlanAgentSlots(jdbc).resolve(user, logical, retryAttempt, "JVM",
                "重新措辞的理由", LearningSourceType.AGENT, null, SECOND)).isEqualTo(existingPlan);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ambiguousRegeneratedCallIsRejectedInsteadOfReusingWrongPlan() {
        UUID user = UUID.randomUUID();
        UUID logical = UUID.randomUUID();
        UUID oldRun = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(user), eq(logical), anyString()))
                .thenReturn(List.of(
                        new LearningPlanAgentSlots.Slot(UUID.randomUUID(), LearningPlanAgentSlots.signature("原因一", FIRST), oldRun),
                        new LearningPlanAgentSlots.Slot(UUID.randomUUID(), LearningPlanAgentSlots.signature("原因二", SECOND), oldRun)));

        assertThatThrownBy(() -> new LearningPlanAgentSlots(jdbc).resolve(user, logical, UUID.randomUUID(),
                "JVM", "改写后的理由", LearningSourceType.AGENT, null, SECOND))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("无法安全判定");
    }

    @Test
    @SuppressWarnings("unchecked")
    void paraphrasedTopicAfterInterruptedRunIsNotSilentlyRecreated() {
        UUID user = UUID.randomUUID();
        UUID logical = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(user), eq(logical), anyString()))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(user), eq(logical), eq(replacement)))
                .thenReturn(true);

        assertThatThrownBy(() -> new LearningPlanAgentSlots(jdbc).resolve(user, logical, replacement,
                "Java 虚拟机", null, LearningSourceType.AGENT, null, FIRST))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不同的学习主题");
    }
}
