package com.projectardor.scheduling.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.projectardor.scheduling.domain.*;
import com.projectardor.scheduling.repository.CalendarTaskRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CalendarTaskUpdateTests {
    @Test void generalTaskEditKeepsIdentityAndOwner() {
        UUID owner = UUID.randomUUID();
        var repository = mock(CalendarTaskRepository.class);
        var task = CalendarTask.create(owner, "Old", "Old description", Instant.now(), CalendarTaskPriority.MEDIUM, CalendarTaskSource.MANUAL);
        when(repository.findOwnedForUpdate(task.getId(), owner)).thenReturn(Optional.of(task));
        var service = new CalendarTaskService(repository);
        Instant later = Instant.now().plusSeconds(3600);
        var updated = service.updateGeneral(owner, task.getId(), "New", "New description", later, CalendarTaskPriority.HIGH, CalendarTaskStatus.IN_PROGRESS);
        assertThat(updated.getId()).isEqualTo(task.getId());
        assertThat(updated.getDueAt()).isEqualTo(later);
        assertThat(updated.getStatus()).isEqualTo(CalendarTaskStatus.IN_PROGRESS);
        assertThatThrownBy(() -> service.updateGeneral(UUID.randomUUID(), task.getId(), "x", null, later, null, null))
                .isInstanceOf(com.projectardor.common.web.ResourceNotFoundException.class);
    }
    @Test void linkedLearningTaskCannotBeRewrittenAsAnIndependentCalendarTask() {
        UUID owner = UUID.randomUUID(); UUID id = UUID.randomUUID();
        var repository = mock(CalendarTaskRepository.class);
        var task = mock(CalendarTask.class);
        when(task.getTaskKind()).thenReturn(CalendarTaskKind.LEARNING);
        when(repository.findOwnedForUpdate(id, owner)).thenReturn(Optional.of(task));
        assertThatThrownBy(() -> new CalendarTaskService(repository).updateGeneral(owner, id, "x", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("对应业务对象");
        verify(task, never()).update(any(), any(), any(), any(), any());
    }
}
