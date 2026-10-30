package com.projectardor.assistant.automation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.assistant.automation.domain.Automation;
import com.projectardor.assistant.automation.repository.AutomationRepository;
import com.projectardor.assistant.automation.web.AutomationResponse;
import com.projectardor.common.web.ResourceNotFoundException;
import tools.jackson.databind.json.JsonMapper;

class AutomationViewTests {
    private final UUID owner = UUID.randomUUID();
    private final AutomationRepository repository = mock(AutomationRepository.class);
    private final AutomationService service = new AutomationService(repository);
    private Automation entity() { return entity(false); }
    private Automation entity(boolean deleteAfterRun) {
        var value = Automation.create(owner, "Daily review", "Synthetic prompt", "0 0 9 * * *",
                "Asia/Shanghai", deleteAfterRun, Instant.parse("2030-01-01T01:00:00Z"));
        ReflectionTestUtils.invokeMethod(value, "onCreate");
        return value;
    }

    @Test void ownedSnapshotsAreDetachedAndKeepTheHttpJson() {
        var entity = entity();
        when(repository.findByIdAndUserId(entity.getId(), owner)).thenReturn(Optional.of(entity));
        when(repository.findAllByUserIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(entity));
        var view = service.getView(owner, entity.getId());
        var json = JsonMapper.builder().findAndAddModules().build();
        tools.jackson.databind.JsonNode actual = json.valueToTree(view);
        tools.jackson.databind.JsonNode response = json.valueToTree(AutomationResponse.from(view));
        assertThat(actual).isEqualTo(response);
        assertThat(actual.properties()).hasSize(13);
        assertThat(view.id()).isEqualTo(entity.getId());
        assertThat(view.deleteAfterRun()).isFalse();
        assertThat(service.listViews(owner)).containsExactly(view);
        entity.update("Changed later", null, null, null, false, null, null);
        assertThat(view.title()).isEqualTo("Daily review");
        assertThat(view.enabled()).isTrue();
        assertThatThrownBy(() -> service.listViews(owner).clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(repository, never()).flush();
    }

    @Test void foreignResourceFailsBeforeMutation() {
        UUID id = UUID.randomUUID(), foreign = UUID.randomUUID();
        assertThatThrownBy(() -> service.getView(foreign, id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.updateView(foreign, id, "x", null, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, times(2)).findByIdAndUserId(id, foreign);
        verify(repository, never()).save(any());
        verify(repository, never()).flush();
    }

    @Test void updateKeepsOtherFieldsAndCapturesTheFlushedTimestamp() {
        var entity = entity();
        when(repository.findByIdAndUserId(entity.getId(), owner)).thenReturn(Optional.of(entity));
        Instant flushed = Instant.parse("2030-01-01T02:00:00Z");
        doAnswer(call -> { ReflectionTestUtils.setField(entity, "updatedAt", flushed); return null; }).when(repository).flush();
        var view = service.updateView(owner, entity.getId(), null, null, null, null, false, null);
        assertThat(view.prompt()).isEqualTo("Synthetic prompt");
        assertThat(view.cronExpression()).isEqualTo("0 0 9 * * *");
        assertThat(view.enabled()).isFalse();
        assertThat(view.nextRunAt()).isNull();
        assertThat(view.updatedAt()).isEqualTo(flushed);
    }

    @Test void creationReusesValidationAndOwnedScheduleRules() {
        when(repository.save(any(Automation.class))).thenAnswer(call -> {
            Automation entity = call.getArgument(0);
            ReflectionTestUtils.invokeMethod(entity, "onCreate");
            assertThat(entity.getUserId()).isEqualTo(owner);
            return entity;
        });
        var view = service.createView(owner, " Review ", " Prompt ", "0 0 9 * * *", null, null);
        assertThat(view.title()).isEqualTo("Review");
        assertThat(view.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(view.deleteAfterRun()).isFalse();
        assertThat(view.nextRunAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(view.createdAt()).isNotNull();
        verify(repository).flush();
        clearInvocations(repository);
        assertThatThrownBy(() -> service.createView(owner, "x", "y", "bad cron", null, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test void successfulOneTimeRunDeletesTheAutomation() {
        var entity = entity(true);
        when(repository.findByIdAndUserId(entity.getId(), owner)).thenReturn(Optional.of(entity));
        service.finish(owner, entity.getId(), true, null);
        verify(repository).delete(entity);
        assertThat(entity.getLastStatus()).isNull();
    }

    @Test void failedOneTimeRunPausesAndKeepsTheAutomation() {
        var entity = entity(true);
        when(repository.findByIdAndUserId(entity.getId(), owner)).thenReturn(Optional.of(entity));
        service.finish(owner, entity.getId(), false, "model unavailable");
        assertThat(entity.isEnabled()).isFalse();
        assertThat(entity.getLastStatus()).isEqualTo("FAILED");
        assertThat(entity.getLastError()).isEqualTo("model unavailable");
        assertThat(entity.getNextRunAt()).isNull();
        verify(repository, never()).delete(any());
    }

    @Test void recurringRunStillSchedulesTheNextOccurrence() {
        var entity = entity();
        when(repository.findByIdAndUserId(entity.getId(), owner)).thenReturn(Optional.of(entity));
        service.finish(owner, entity.getId(), true, null);
        assertThat(entity.isEnabled()).isTrue();
        assertThat(entity.getLastStatus()).isEqualTo("COMPLETED");
        assertThat(entity.getNextRunAt()).isAfter(Instant.now());
        verify(repository, never()).delete(any());
    }

    @Test void oneTimeFlagCanBeUpdatedWithoutChangingTheSchedule() {
        var entity = entity();
        when(repository.findByIdAndUserId(entity.getId(), owner)).thenReturn(Optional.of(entity));
        Instant next = entity.getNextRunAt();
        var view = service.updateView(owner, entity.getId(), null, null, null, null, null, true);
        assertThat(view.deleteAfterRun()).isTrue();
        assertThat(view.enabled()).isTrue();
        assertThat(view.nextRunAt()).isEqualTo(next);
    }

    @Test void snapshotMappingHasATransactionBoundary() throws Exception {
        assertThat(AutomationService.class.getMethod("listViews", UUID.class).getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(AutomationService.class.getMethod("getView", UUID.class, UUID.class).getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(AutomationService.class.getMethod("createView", UUID.class, String.class, String.class, String.class, String.class, Boolean.class)
                .getAnnotation(Transactional.class).readOnly()).isFalse();
    }
}
