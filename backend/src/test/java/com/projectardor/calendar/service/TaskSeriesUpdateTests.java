package com.projectardor.calendar.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.projectardor.calendar.domain.*;
import com.projectardor.calendar.repository.*;
import com.projectardor.profile.service.ProfileService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskSeriesUpdateTests {
    @Test void sameDayEligibilityUsesOriginalOccurrenceTime() {
        LocalDate date = LocalDate.of(2026, 10, 15);
        Instant now = date.atTime(8, 0).toInstant(ZoneOffset.UTC);
        for (boolean alreadyPast : List.of(true, false)) {
            UUID owner = UUID.randomUUID();
            LocalTime original = LocalTime.of(alreadyPast ? 7 : 9, 0);
            LocalTime revised = LocalTime.of(alreadyPast ? 9 : 7, 0);
            TaskSeries series = TaskSeries.create(owner, "Meeting", "Original", CalendarTaskPriority.MEDIUM,
                    CalendarTaskSource.AGENT, ZoneOffset.UTC, RecurrenceFrequency.DAILY, 1, Set.of(), null,
                    original, date, null, null);
            CalendarTask occurrence = CalendarTask.createOccurrence(series, date);
            UUID occurrenceId = occurrence.getId();
            var rules = mock(TaskSeriesRepository.class);
            var tasks = mock(CalendarTaskRepository.class);
            when(rules.findOwnedForUpdate(series.getId(), owner)).thenReturn(Optional.of(series));
            when(tasks.findSeriesForUpdate(series.getId(), owner)).thenReturn(List.of(occurrence));
            var service = new TaskSeriesService(rules, tasks, mock(ProfileService.class));
            var result = service.updateDetailsAt(owner, series.getId(), null, null, null, revised, now);
            assertThat(result.updatedOccurrences()).isEqualTo(alreadyPast ? 0 : 1);
            assertThat(result.preservedOccurrences()).isEqualTo(alreadyPast ? 1 : 0);
            assertThat(occurrence.getId()).isEqualTo(occurrenceId);
            assertThat(occurrence.getDueAt()).isEqualTo(date.atTime(alreadyPast ? original : revised).toInstant(ZoneOffset.UTC));
        }
    }

    @Test void updatingTimePreservesIdsHistoryAndIndividuallyEditedOccurrences() {
        UUID owner = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        TaskSeries series = TaskSeries.create(owner, "Meeting", "Original", CalendarTaskPriority.MEDIUM,
                CalendarTaskSource.AGENT, ZoneOffset.UTC, RecurrenceFrequency.DAILY, 1, Set.of(), null,
                LocalTime.of(7,0), today.minusDays(2), null, null);
        CalendarTask past = CalendarTask.createOccurrence(series, today.minusDays(1));
        CalendarTask future = CalendarTask.createOccurrence(series, today.plusDays(1));
        CalendarTask edited = CalendarTask.createOccurrence(series, today.plusDays(2));
        edited.update("Personal exception", "Keep me", edited.getDueAt(), CalendarTaskPriority.HIGH, CalendarTaskStatus.TODO);
        CalendarTask completed = CalendarTask.createOccurrence(series, today.plusDays(3));
        completed.update(completed.getTitle(), completed.getDescription(), completed.getDueAt(), completed.getPriority(), CalendarTaskStatus.COMPLETED);
        Instant completedAt = completed.getCompletedAt(); Instant pastTime = past.getDueAt(); Instant editedTime = edited.getDueAt();
        UUID futureId = future.getId(); UUID seriesId = series.getId();
        var rules = mock(TaskSeriesRepository.class); var tasks = mock(CalendarTaskRepository.class);
        when(rules.findOwnedForUpdate(seriesId, owner)).thenReturn(Optional.of(series));
        when(tasks.findSeriesForUpdate(seriesId, owner)).thenReturn(List.of(past, future, edited, completed));
        var service = new TaskSeriesService(rules, tasks, mock(ProfileService.class));
        var result = service.updateDetails(owner, seriesId, "Updated meeting", null, null, LocalTime.of(9,0));
        assertThat(result.updatedOccurrences()).isEqualTo(1);
        assertThat(result.preservedOccurrences()).isEqualTo(3);
        assertThat(result.series().getId()).isEqualTo(seriesId);
        assertThat(future.getId()).isEqualTo(futureId);
        assertThat(future.getDueAt()).isEqualTo(today.plusDays(1).atTime(9,0).toInstant(ZoneOffset.UTC));
        assertThat(future.isUserModified()).isFalse();
        assertThat(past.getDueAt()).isEqualTo(pastTime);
        assertThat(edited.getDueAt()).isEqualTo(editedTime);
        assertThat(edited.getTitle()).isEqualTo("Personal exception");
        assertThat(completed.getCompletedAt()).isEqualTo(completedAt);
        verify(tasks, never()).save(any()); verify(tasks, never()).deleteAll(any());
        assertThatThrownBy(() -> service.updateDetails(UUID.randomUUID(), seriesId, "Foreign", null, null, null))
                .isInstanceOf(com.projectardor.common.web.ResourceNotFoundException.class);
        series.cancel();
        assertThatThrownBy(() -> service.updateDetails(owner, seriesId, "Revive", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
