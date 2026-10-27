package com.projectardor.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.scheduling.domain.CalendarTask;
import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.repository.CalendarTaskRepository;

class CalendarTaskServiceIdempotencyTests {
    private static final Instant DUE = Instant.parse("2028-04-01T01:00:00Z");
    private final UUID owner = UUID.randomUUID(), action = UUID.randomUUID(), run = UUID.randomUUID();
    private final CalendarTaskRepository repository = mock(CalendarTaskRepository.class);
    private final CalendarTaskAgentSlots slots = mock(CalendarTaskAgentSlots.class);
    private final CalendarTaskService service = new CalendarTaskService(repository, slots);

    @Test
    void lostResponseRetryReturnsTheCommittedTaskWithoutCreatingAgain() {
        CalendarTask committed = CalendarTask.create(owner, "Review", "First description", DUE,
                CalendarTaskPriority.HIGH, CalendarTaskSource.AGENT, null);
        when(slots.existing(any(), any(), any(), any())).thenReturn(Optional.of(committed.getId()));
        when(repository.findByIdAndUserId(committed.getId(), owner)).thenReturn(Optional.of(committed));

        CalendarTaskView result = service.createViewOnce(owner, action, UUID.randomUUID(), "Review",
                "Rewritten description", DUE, CalendarTaskPriority.HIGH, CalendarTaskSource.AGENT, null);

        assertThat(result.id()).isEqualTo(committed.getId());
        verify(repository, never()).save(any());
        verify(slots, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void firstCreateRecordsTheSemanticSlotWithTheNewTask() {
        when(slots.existing(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarTaskView created = service.createViewOnce(owner, action, run, "Review", "Description",
                DUE, CalendarTaskPriority.HIGH, CalendarTaskSource.AGENT, null);

        verify(slots).record(owner, action, run,
                CalendarTaskAgentSlots.semanticKey("Review", DUE, CalendarTaskPriority.HIGH, null),
                CalendarTaskAgentSlots.callSignature("Description"), created.id());
    }

    @Test
    void noLogicalActionFallsBackToAPlainCreate() {
        CalendarTask task = CalendarTask.create(owner, "Review", "", DUE, CalendarTaskPriority.MEDIUM,
                CalendarTaskSource.AGENT);
        when(repository.save(any())).thenReturn(task);

        service.createViewOnce(owner, null, run, "Review", "", DUE, CalendarTaskPriority.MEDIUM,
                CalendarTaskSource.AGENT, null);

        verify(slots, never()).existing(any(), any(), any(), any());
        verify(repository).save(any());
    }
}
