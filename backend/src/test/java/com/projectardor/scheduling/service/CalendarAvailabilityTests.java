package com.projectardor.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.scheduling.domain.CalendarTask;
import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.domain.CalendarTaskStatus;
import com.projectardor.scheduling.repository.CalendarTaskRepository;

class CalendarAvailabilityTests {
    private static final Instant DAY_START = Instant.parse("2026-11-04T10:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-11-04T13:00:00Z");

    @Test void freeSlotsExcludeTheBlockOccupiedByAFixedSeriesEntry() {
        UUID owner = UUID.randomUUID();
        var meeting = fixedSeriesEntry("组会", Instant.parse("2026-11-04T11:00:00Z"));
        var service = service(owner, List.of(meeting));

        var window = service.findFreeSlots(owner, DAY_START, DAY_END, 60, 60, null);

        assertThat(window.busy()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("组会");
            assertThat(item.fixedSeries()).isTrue();
        });
        assertThat(window.freeSlots()).containsExactly(
                new CalendarAvailabilityTypes.Slot(DAY_START, Instant.parse("2026-11-04T11:00:00Z")),
                new CalendarAvailabilityTypes.Slot(Instant.parse("2026-11-04T12:00:00Z"), DAY_END));
    }

    @Test void candidateEqualToAnExistingMeetingIsReportedAsAConflict() {
        UUID owner = UUID.randomUUID();
        var meeting = fixedSeriesEntry("组会", Instant.parse("2026-11-04T11:00:00Z"));
        var service = service(owner, List.of(meeting));

        assertThat(service.conflictsFor(owner, Instant.parse("2026-11-04T11:00:00Z"), 60, 60, null))
                .singleElement().extracting(CalendarAvailabilityTypes.BusyItem::title).isEqualTo("组会");
        assertThat(service.conflictsFor(owner, Instant.parse("2026-11-04T10:30:00Z"), 60, 60, null))
                .hasSize(1);
        assertThat(service.conflictsFor(owner, Instant.parse("2026-11-04T12:00:00Z"), 60, 60, null)).isEmpty();
    }

    @Test void finishedAndCanceledEntriesDoNotOccupyTime() {
        UUID owner = UUID.randomUUID();
        var done = entry("已完成的复习", Instant.parse("2026-11-04T11:00:00Z"), CalendarTaskStatus.COMPLETED);
        var canceled = entry("取消的面试", Instant.parse("2026-11-04T11:00:00Z"), CalendarTaskStatus.CANCELLED);
        var service = service(owner, List.of(done, canceled));

        var window = service.findFreeSlots(owner, DAY_START, DAY_END, 60, 60, null);

        assertThat(window.busy()).isEmpty();
        assertThat(window.freeSlots()).containsExactly(new CalendarAvailabilityTypes.Slot(DAY_START, DAY_END));
    }

    @Test void excludingTheEditedEntryKeepsItFromBlockingItself() {
        UUID owner = UUID.randomUUID();
        var moving = entry("复习 MySQL 事务", Instant.parse("2026-11-04T11:00:00Z"), CalendarTaskStatus.TODO);
        var service = service(owner, List.of(moving));

        assertThat(service.conflictsFor(owner, Instant.parse("2026-11-04T11:00:00Z"), 60, 60, moving.getId()))
                .isEmpty();
        assertThat(service.conflictsFor(owner, Instant.parse("2026-11-04T11:00:00Z"), 60, 60, null)).hasSize(1);
    }

    @Test void onlyTheCallerOwnsTheEntriesUsedForAvailability() {
        UUID owner = UUID.randomUUID();
        var other = UUID.randomUUID();
        var repository = mock(CalendarTaskRepository.class);
        var series = fixedSeriesEntry("组会", Instant.parse("2026-11-04T11:00:00Z"));
        when(repository.findAllByUserIdOrderByDueAtAscCreatedAtDesc(owner)).thenReturn(List.of(series));
        when(repository.findAllByUserIdOrderByDueAtAscCreatedAtDesc(other)).thenReturn(List.of());
        var service = new CalendarTaskService(repository);

        assertThat(service.findFreeSlots(owner, DAY_START, DAY_END, 60, 60, null).busy()).hasSize(1);
        assertThat(service.findFreeSlots(other, DAY_START, DAY_END, 60, 60, null).busy()).isEmpty();
    }

    @Test void invalidWindowsAndDurationsFailInsteadOfGuessing() {
        UUID owner = UUID.randomUUID();
        var service = service(owner, List.of());

        assertThatThrownBy(() -> service.findFreeSlots(owner, DAY_END, DAY_START, 60, 60, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("开始早于结束");
        assertThatThrownBy(() -> service.findFreeSlots(owner, DAY_START, DAY_START.plusSeconds(86_400L * 40), 60, 60, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("31 天");
        assertThatThrownBy(() -> service.findFreeSlots(owner, DAY_START, DAY_END, 0, 60, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("正数");
        assertThatThrownBy(() -> service.conflictsFor(owner, null, 60, 60, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("候选时间");
    }

    @Test void blocksTouchingTheWindowEdgesStillShapeTheGaps() {
        UUID owner = UUID.randomUUID();
        var early = entry("提前开始的面试", DAY_START.minusSeconds(1_800), CalendarTaskStatus.TODO);
        var late = entry("深夜组会", DAY_END.minusSeconds(30), CalendarTaskStatus.TODO);
        var service = service(owner, List.of(early, late));

        var window = service.findFreeSlots(owner, DAY_START, DAY_END, 60, 60, null);

        assertThat(window.busy()).hasSize(2);
        assertThat(window.freeSlots()).singleElement().satisfies(slot -> {
            assertThat(slot.start()).isEqualTo(DAY_START.plusSeconds(1_800));
            assertThat(slot.end()).isEqualTo(DAY_END.minusSeconds(30));
        });
    }

    private static CalendarTaskService service(UUID owner, List<CalendarTask> tasks) {
        var repository = mock(CalendarTaskRepository.class);
        when(repository.findAllByUserIdOrderByDueAtAscCreatedAtDesc(owner)).thenReturn(tasks);
        return new CalendarTaskService(repository);
    }

    private static CalendarTask entry(String title, Instant dueAt, CalendarTaskStatus status) {
        var task = CalendarTask.create(UUID.randomUUID(), title, "", dueAt,
                CalendarTaskPriority.MEDIUM, CalendarTaskSource.MANUAL);
        if (status != CalendarTaskStatus.TODO) task.update(title, "", dueAt, CalendarTaskPriority.MEDIUM, status);
        return task;
    }

    private static CalendarTask fixedSeriesEntry(String title, Instant dueAt) {
        var task = mock(CalendarTask.class);
        when(task.getId()).thenReturn(UUID.randomUUID());
        when(task.getTitle()).thenReturn(title);
        when(task.getDueAt()).thenReturn(dueAt);
        when(task.getStatus()).thenReturn(CalendarTaskStatus.TODO);
        when(task.getSeriesId()).thenReturn(UUID.randomUUID());
        return task;
    }
}
