package com.projectardor.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;

import com.projectardor.calendar.service.CalendarTaskService;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.learning.domain.LearningPlan;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.learning.repository.LearningPlanRepository;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.profile.service.ProfileService;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;
import com.projectardor.recap.repository.InterviewRecapRepository;

import tools.jackson.databind.ObjectMapper;

class LearningPlanIdempotencyTests {
    private static final Instant DUE = Instant.parse("2026-10-08T01:00:00Z");

    @Test
    void repeatedRequestReturnsExistingPlanWithoutAnotherCalendarTask() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LearningPlan plan = plan(userId, requestId, "same-input");
        LearningPlanRepository repository = mock(LearningPlanRepository.class);
        CalendarTaskService calendar = mock(CalendarTaskService.class);
        when(repository.findByUserIdAndRequestId(userId, requestId))
                .thenReturn(Optional.empty(), Optional.of(plan));
        when(repository.saveAndFlush(plan)).thenReturn(plan);
        LearningPlanCreationStore store = new LearningPlanCreationStore(repository, calendar, mock(JdbcTemplate.class));

        assertThat(store.save(userId, requestId, plan)).isSameAs(plan);
        assertThat(store.save(userId, requestId, plan(userId, requestId, "same-input"))).isSameAs(plan);

        verify(repository, times(2)).lockRequestSlot(userId, requestId);
        verify(repository, times(1)).saveAndFlush(any(LearningPlan.class));
        verify(calendar, times(1)).refreshLearningPlan(eq(userId), eq(plan.getId()),
                eq("JVM"), eq("面试薄弱点"), eq(DUE), any(), eq(false));
    }

    @Test
    void requestIdReusedWithDifferentInputIsRejected() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LearningPlanRepository repository = mock(LearningPlanRepository.class);
        when(repository.findByUserIdAndRequestId(userId, requestId))
                .thenReturn(Optional.of(plan(userId, requestId, "original-input")));
        LearningPlanCreationStore store = new LearningPlanCreationStore(repository, mock(CalendarTaskService.class),
                mock(JdbcTemplate.class));

        assertThatThrownBy(() -> store.existing(userId, requestId, "changed-input"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请求 ID");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void differentUsersCannotReuseEachOthersResult() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LearningPlanRepository repository = mock(LearningPlanRepository.class);
        when(repository.findByUserIdAndRequestId(owner, requestId))
                .thenReturn(Optional.of(plan(owner, requestId, "same-input")));
        when(repository.findByUserIdAndRequestId(other, requestId)).thenReturn(Optional.empty());
        LearningPlanCreationStore store = new LearningPlanCreationStore(repository, mock(CalendarTaskService.class),
                mock(JdbcTemplate.class));

        assertThat(store.existing(other, requestId, "same-input")).isNull();
        verify(repository).findByUserIdAndRequestId(other, requestId);
    }

    @Test
    void deletedPlanCannotBeRecreatedByDelayedRetry() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LearningPlanRepository repository = mock(LearningPlanRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LearningPlan original = plan(userId, requestId, "same-input");
        when(repository.findByUserIdAndRequestId(userId, requestId)).thenReturn(Optional.empty());
        when(jdbc.queryForList(anyString(), eq(String.class), eq(userId), eq(requestId)))
                .thenReturn(List.of("same-input"));
        LearningPlanCreationStore store = new LearningPlanCreationStore(repository,
                mock(CalendarTaskService.class), jdbc);

        store.markDeleted(original);
        verify(jdbc).update(anyString(), eq(userId), eq(requestId), eq("same-input"));
        assertThatThrownBy(() -> store.existing(userId, requestId, "same-input"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已删除");
    }

    @Test
    void completedRequestSkipsModelGeneration() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LearningPlan previous = plan(userId, requestId, "saved-input");
        LearningPlanCreationStore store = mock(LearningPlanCreationStore.class);
        when(store.existing(eq(userId), eq(requestId), anyString())).thenReturn(previous);
        LlmGateway llm = mock(LlmGateway.class);
        LearningPlanService service = new LearningPlanService(mock(LearningPlanRepository.class), llm,
                mock(LlmJsonParser.class), mock(ObjectMapper.class), mock(CalendarTaskService.class),
                mock(ProfileService.class), mock(InterviewRecapRepository.class),
                mock(InterviewRecapQuestionRepository.class), mock(com.projectardor.resume.repository.ResumeRepository.class),
                mock(com.projectardor.knowledge.repository.KnowledgeDocumentRepository.class),
                store, mock(LearningPlanGenerationGate.class),
                mock(LearningPlanAgentSlots.class));

        assertThat(service.create(userId, requestId, "JVM", "面试薄弱点",
                LearningSourceType.MANUAL, null, DUE)).isSameAs(previous);
        verify(llm, never()).completeJson(any(), any(), any());
    }

    @Test
    void agentRetryIgnoresRegeneratedReasonAndSchedule() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LearningPlan previous = plan(userId, requestId, "saved-input");
        LearningPlanCreationStore store = mock(LearningPlanCreationStore.class);
        when(store.existing(eq(userId), eq(requestId), anyString())).thenReturn(previous);
        LearningPlanAgentSlots slots = mock(LearningPlanAgentSlots.class);
        UUID runId = UUID.randomUUID();
        when(slots.resolve(eq(userId), eq(requestId), eq(runId), eq("JVM"), anyString(),
                eq(LearningSourceType.RECAP), eq(null), any(Instant.class))).thenReturn(requestId);
        LlmGateway llm = mock(LlmGateway.class);
        LearningPlanService service = new LearningPlanService(mock(LearningPlanRepository.class), llm,
                mock(LlmJsonParser.class), mock(ObjectMapper.class), mock(CalendarTaskService.class),
                mock(ProfileService.class), mock(InterviewRecapRepository.class),
                mock(InterviewRecapQuestionRepository.class), mock(com.projectardor.resume.repository.ResumeRepository.class),
                mock(com.projectardor.knowledge.repository.KnowledgeDocumentRepository.class),
                store, mock(LearningPlanGenerationGate.class), slots);

        assertThat(service.createForAgent(userId, requestId, runId, "JVM", "第一次说明",
                LearningSourceType.RECAP, null, DUE)).isSameAs(previous);
        assertThat(service.createForAgent(userId, requestId, runId, "JVM", "改写后的说明",
                LearningSourceType.RECAP, null, DUE.plusSeconds(86_400))).isSameAs(previous);
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(store, times(2)).existing(eq(userId), eq(requestId), hashes.capture());
        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
        verify(llm, never()).completeJson(any(), any(), any());
    }

    private LearningPlan plan(UUID userId, UUID requestId, String hash) {
        return LearningPlan.create(userId, requestId, hash, "JVM", "面试薄弱点", LearningSourceType.MANUAL,
                null, Map.of("summary", "JVM"), List.of(Map.of("question", "介绍 JVM")), DUE, "test-model");
    }
}
