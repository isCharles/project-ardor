package com.projectardor.scheduling.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.scheduling.domain.*;
import com.projectardor.scheduling.repository.CalendarTaskRepository;
import com.projectardor.scheduling.web.CalendarTaskResponse;
import tools.jackson.databind.json.JsonMapper;

class CalendarTaskViewTests {
    @Test void deletionTargetDetailUsesOwnedReadWithoutWriting() {
        var task = task();
        when(repository.findByIdAndUserId(task.getId(), owner)).thenReturn(Optional.of(task));
        var view = service.detail(owner, task.getId());
        assertThat(view.id()).isEqualTo(task.getId());
        assertThat(view.title()).isEqualTo(task.getTitle());
        assertThat(view.dueAt()).isEqualTo(task.getDueAt());
        assertThatThrownBy(() -> service.detail(UUID.randomUUID(), task.getId())).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any()); verify(repository, never()).flush();
    }
    private final UUID owner = UUID.randomUUID();
    private final Instant due = Instant.parse("2027-01-01T09:00:00Z");
    private final CalendarTaskRepository repository = mock(CalendarTaskRepository.class);
    private final CalendarTaskService service = new CalendarTaskService(repository);

    @Test void preservesExactLegacyJsonAndDoesNotExposeOwnerOrInternalLinks() {
        UUID interview = UUID.randomUUID();
        var task = CalendarTask.create(owner, "面试", null, due, CalendarTaskPriority.HIGH, CalendarTaskSource.AGENT, interview);
        ReflectionTestUtils.setField(task, "createdAt", due.minusSeconds(60));
        ReflectionTestUtils.setField(task, "updatedAt", due.minusSeconds(30));
        var expected = new CalendarTaskResponse(task.getId(), "面试", null, CalendarTaskStatus.TODO,
                CalendarTaskPriority.HIGH, CalendarTaskSource.AGENT, CalendarTaskKind.GENERAL,
                null, null, interview, due, null, due.minusSeconds(60), due.minusSeconds(30));
        var mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(expected);
        assertThat(mapper.writeValueAsString(CalendarTaskView.from(task))).isEqualTo(json);
        assertThat(mapper.writeValueAsString(CalendarTaskResponse.from(task))).isEqualTo(json);
        assertThat(mapper.writeValueAsString(CalendarTaskResponse.from(CalendarTaskView.from(task)))).isEqualTo(json);
        assertThat(mapper.readTree(json).propertyNames()).containsExactly("id", "title", "description", "status",
                "priority", "source", "taskKind", "actionPath", "seriesId", "jobOpportunityId", "dueAt",
                "completedAt", "createdAt", "updatedAt");
    }

    @Test void listKeepsOwnerAndTimeWindowAndReturnsDetachedSnapshot() {
        var task = task();
        when(repository.findAllByUserIdOrderByDueAtAscCreatedAtDesc(owner)).thenReturn(List.of(task));
        var views = service.listViews(owner, due, due.plusSeconds(1));
        assertThat(views).hasSize(1);
        task.update("Changed", null, due, CalendarTaskPriority.HIGH, CalendarTaskStatus.COMPLETED);
        assertThat(views.getFirst().title()).isEqualTo("Original");
        assertThat(views.getFirst().status()).isEqualTo(CalendarTaskStatus.TODO);
        assertThat(service.listViews(owner, due.plusSeconds(1), due.plusSeconds(2))).isEmpty();
        assertThatThrownBy(() -> service.listViews(owner, due, due)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void writesFlushBeforeTakingLifecycleTimestampSnapshot() {
        var task = task();
        when(repository.findOwnedForUpdate(task.getId(), owner)).thenReturn(Optional.of(task));
        doAnswer(invocation -> {ReflectionTestUtils.setField(task, "updatedAt", due); return null;}).when(repository).flush();
        var view = service.updateGeneralView(owner, task.getId(), "Updated", "detail", due,
                CalendarTaskPriority.HIGH, CalendarTaskStatus.COMPLETED);
        assertThat(view.id()).isEqualTo(task.getId());
        assertThat(view.updatedAt()).isEqualTo(due);
        assertThat(view.completedAt()).isNotNull();
        verify(repository).flush();
    }

    @Test void creationKeepsDefaultsAndFlushesBeforeSnapshot() {
        when(repository.save(any(CalendarTask.class))).thenAnswer(invocation -> {
            CalendarTask saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "createdAt", due);
            return saved;
        });
        var view = service.createView(owner, "  New  ", "  ", null, null, null, null);
        assertThat(view.title()).isEqualTo("New");
        assertThat(view.description()).isNull();
        assertThat(view.priority()).isEqualTo(CalendarTaskPriority.MEDIUM);
        assertThat(view.source()).isEqualTo(CalendarTaskSource.MANUAL);
        assertThat(view.createdAt()).isEqualTo(due);
        verify(repository).flush();
    }

    @Test void rejectsForeignAndBusinessMaintainedTasksWithoutFlushingOrDeleting() {
        UUID foreign = UUID.randomUUID();
        when(repository.findOwnedForUpdate(foreign, owner)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateGeneralView(owner, foreign, "x", null, due, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        var review = CalendarTask.createMemoryReview(owner, LocalDate.of(2027, 1, 1), 2, due);
        when(repository.findOwnedForUpdate(review.getId(), owner)).thenReturn(Optional.of(review));
        assertThatThrownBy(() -> service.updateGeneralView(owner, review.getId(), "x", null, due, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).flush();
        verify(repository, never()).delete(any());
    }

    private CalendarTask task() {
        return CalendarTask.create(owner, "Original", null, due, CalendarTaskPriority.MEDIUM, CalendarTaskSource.MANUAL);
    }
}
