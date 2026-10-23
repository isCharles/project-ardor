package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.projectardor.materials.resume.domain.ResumeAnalysisJobStatus;
import com.projectardor.materials.resume.service.ResumeAnalysisQueueService;
import com.projectardor.materials.resume.web.ResumeAnalysisTaskResponse;
import com.projectardor.jobsearch.record.domain.InterviewRecapJobStatus;
import com.projectardor.jobsearch.record.service.InterviewRecapQueueService;
import com.projectardor.jobsearch.record.service.InterviewRecordTaskView;
import com.projectardor.jobsearch.record.web.InterviewRecapTaskResponse;
import com.projectardor.scheduling.advice.domain.*;
import com.projectardor.scheduling.advice.repository.CalendarAdviceRepository;
import com.projectardor.scheduling.advice.service.*;
import com.projectardor.scheduling.advice.web.CalendarAdviceResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ApplicationToolResultContractTests {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @ParameterizedTest @EnumSource(ResumeAnalysisJobStatus.class)
    void resumeTaskKeepsSevenFieldsInEveryState(ResumeAnalysisJobStatus status) {
        var task = new ResumeAnalysisQueueService.TaskResult(UUID.randomUUID(), UUID.randomUUID(), status,
                status == ResumeAnalysisJobStatus.COMPLETED ? UUID.randomUUID() : null, 2,
                status == ResumeAnalysisJobStatus.FAILED ? "SYNTHETIC" : null, null);
        JsonNode actual = json.valueToTree(task);
        JsonNode http = json.valueToTree(ResumeAnalysisTaskResponse.from(task));
        assertThat(actual).isEqualTo(http);
        assertThat(actual.properties()).hasSize(7);
        assertThat(actual.propertyNames()).containsExactly("jobId", "resumeId", "status", "analysisId", "attempts", "errorCode", "errorMessage");
        assertThat(actual.path("status").asText()).isEqualTo(status.name());
        assertThat(actual.path("errorMessage").isNull()).isTrue();
    }

    @ParameterizedTest @EnumSource(InterviewRecapJobStatus.class)
    void recordTaskKeepsTenFieldsAndBothWorkflowPaths(InterviewRecapJobStatus status) {
        UUID job = UUID.randomUUID();
        for (boolean finalMinutes : List.of(false, true)) {
            var task = new InterviewRecapQueueService.TaskResult(job, null, status, 2, null, null,
                    Instant.parse("2026-01-01T00:00:00Z"), null, finalMinutes);
            var view = InterviewRecordTaskView.from(task);
            JsonNode actual = json.valueToTree(view), http = json.valueToTree(InterviewRecapTaskResponse.from(task));
            assertThat(actual).isEqualTo(http);
            assertThat(actual.properties()).hasSize(10);
            assertThat(actual.propertyNames()).containsExactly("jobId", "recapId", "status", "attempts", "errorCode",
                    "errorMessage", "createdAt", "finishedAt", "nextAction", "draftUrl");
            assertThat(view.status()).isEqualTo(status.name());
            assertThat(view.draftUrl()).isEqualTo("/app/recaps?" + (finalMinutes ? "minutes=" : "draft=") + job);
            assertThat(view.nextAction()).isEqualTo(finalMinutes
                    ? "最终纪要已保存，后台正在规范化整理，可稍后查看结果。"
                    : "原始转录已保存。初步纪要需用户校正补充并在界面确认后，才生成详细复盘。");
            assertThat(actual.has("finalMinutes")).isFalse();
        }
        var completed = InterviewRecordTaskView.from(new InterviewRecapQueueService.TaskResult(null, UUID.randomUUID(),
                InterviewRecapJobStatus.COMPLETED, 0, null, null, Instant.now(), null));
        assertThat(completed.draftUrl()).isNull();
        assertThat(completed.nextAction()).isEqualTo("打开已有复盘");
    }

    @Test void adviceNormalizationIsOwnedImmutableAndWireCompatible() {
        var repository = mock(CalendarAdviceRepository.class);
        var service = new CalendarAdviceService(repository);
        UUID owner = UUID.randomUUID(), foreign = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var input = new ArrayList<>(Arrays.asList("- One。Long explanation", "- One。Long explanation", null, "Two", "Three", "Four"));
        var view = service.save(owner, "Headline", input, null, List.of());
        input.clear();
        assertThat(view.headline()).isEqualTo("Headline");
        assertThat(view.days()).containsExactly("One。", "Two", "Three");
        assertThat(view.weeks()).isEmpty();
        assertThat(view.updatedAt()).isNotNull();
        assertThatThrownBy(() -> view.days().add("changed")).isInstanceOf(UnsupportedOperationException.class);
        JsonNode app = json.valueToTree(view), http = json.valueToTree(CalendarAdviceResponse.from(view));
        assertThat(app).isEqualTo(http);
        assertThat(app.properties()).hasSize(5);
        assertThat(app.propertyNames()).containsExactly("headline", "days", "weeks", "months", "updatedAt");
        assertThat(service.get(foreign)).isEqualTo(CalendarAdviceView.empty());
        verify(repository).findById(owner);
        verify(repository).findById(foreign);
        service.delete(owner);
        verify(repository).deleteById(owner);
        verify(repository, never()).deleteById(foreign);
    }

    @Test void legacyAdviceRemainsReadableWithoutWritingOrInventingMetadata() {
        var repository = mock(CalendarAdviceRepository.class);
        UUID owner = UUID.randomUUID();
        var entity = CalendarAdvice.create(owner, new CalendarAdvicePayload(null, List.of(), List.of(), List.of()));
        ReflectionTestUtils.setField(entity, "payload", null);
        ReflectionTestUtils.setField(entity, "days", "- First。\n2. Second。");
        when(repository.findById(owner)).thenReturn(Optional.of(entity));
        var view = new CalendarAdviceService(repository).get(owner);
        assertThat(view.headline()).isNull();
        assertThat(view.days()).containsExactly("First。", "Second。");
        assertThat(view.updatedAt()).isEqualTo(entity.getUpdatedAt());
        verify(repository, never()).save(any());
    }

    @Test void emptyAdviceAndOversizedItemsKeepExistingRules() {
        var repository = mock(CalendarAdviceRepository.class);
        var service = new CalendarAdviceService(repository);
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> service.save(owner, "title", List.of(), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var view = service.save(owner, "H".repeat(200), List.of("D".repeat(200)), List.of(), List.of());
        assertThat(view.headline()).hasSize(120).endsWith("…");
        assertThat(view.days().getFirst()).hasSize(80).endsWith("…");
        var values = new ArrayList<>(List.of("stable"));
        var detached = new CalendarAdviceView(null, values, List.of(), List.of(), null);
        values.clear();
        assertThat(detached.days()).containsExactly("stable");
    }
}
