package com.projectardor.scheduling.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.platform.identity.profile.domain.UserProfile;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.scheduling.domain.*;
import com.projectardor.scheduling.repository.*;
import com.projectardor.scheduling.web.TaskSeriesResponse;
import tools.jackson.databind.json.JsonMapper;

class TaskSeriesViewTests {
    @Test void deletionMetadataDoesNotQueryOrMaterializeOccurrences() {
        var rule = weekly();
        when(rules.findByIdAndUserId(rule.getId(), owner)).thenReturn(Optional.of(rule));
        var view = service.metadata(owner, rule.getId());
        assertThat(view.id()).isEqualTo(rule.getId());
        assertThat(view.title()).isEqualTo(rule.getTitle());
        assertThat(view.summary()).isEqualTo(rule.summary());
        assertThat(view.upcoming()).isEmpty();
        assertThatThrownBy(() -> service.metadata(UUID.randomUUID(), rule.getId())).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(tasks, profiles);
        verify(rules, never()).save(any()); verify(rules, never()).flush();
    }
    private final UUID owner = UUID.randomUUID();
    private final TaskSeriesRepository rules = mock(TaskSeriesRepository.class);
    private final CalendarTaskRepository tasks = mock(CalendarTaskRepository.class);
    private final ProfileService profiles = mock(ProfileService.class);
    private final TaskSeriesService service = new TaskSeriesService(rules, tasks, profiles);
    private final LocalDate start = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1);

    @Test void legacyJsonHasSameFieldsSortedWeekdaysAndNullHandling() {
        var series = weekly();
        var dates = new ArrayList<>(List.of(start.plusDays(1), start.plusDays(3)));
        var expected = new TaskSeriesResponse(series.getId(), "Meeting", null, series.summary(),
                CalendarTaskPriority.MEDIUM, CalendarTaskSource.AGENT, "Asia/Shanghai", RecurrenceFrequency.WEEKLY,
                1, List.of("MONDAY", "THURSDAY"), null, LocalTime.of(19, 0), start, null, 4,
                TaskSeriesStatus.ACTIVE, null, dates, null);
        var mapper = JsonMapper.builder().build();
        var view = TaskSeriesView.from(series, dates);
        String expectedJson = mapper.writeValueAsString(expected);
        assertThat(mapper.writeValueAsString(view)).isEqualTo(expectedJson);
        assertThat(mapper.writeValueAsString(TaskSeriesResponse.from(view))).isEqualTo(expectedJson);
        assertThat(mapper.writeValueAsString(TaskSeriesResponse.from(series, dates))).isEqualTo(expectedJson);
        assertThat(mapper.readTree(expectedJson).propertyNames()).containsExactly("id", "title", "description",
                "summary", "priority", "source", "timezone", "frequency", "interval", "weekdays", "monthDay",
                "timeOfDay", "startDate", "untilDate", "occurrenceLimit", "status", "materializedThrough", "upcoming", "createdAt");
        dates.clear();
        assertThat(view.upcoming()).hasSize(2);
        assertThatThrownBy(() -> view.upcoming().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.weekdays().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(TaskSeriesView.from(series, null).upcoming()).isEmpty();
    }

    @Test void listUsesOwnedRulesAndMaterializedOwnedDates() {
        var series = weekly();
        when(profiles.view(owner)).thenReturn(com.projectardor.platform.identity.profile.service.ProfileView.from(UserProfile.create(owner, "Fixture")));
        when(rules.findAllByUserIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(series));
        when(tasks.findAllBySeriesIdAndUserId(series.getId(), owner)).thenReturn(List.of(
                CalendarTask.createOccurrence(series, start.plusDays(3)), CalendarTask.createOccurrence(series, start)));
        var view = service.listViews(owner, 1).getFirst();
        assertThat(view.id()).isEqualTo(series.getId());
        assertThat(view.upcoming()).containsExactly(start);
        verify(tasks).findAllBySeriesIdAndUserId(series.getId(), owner);
    }

    @Test void updateKeepsCountsAndOwnershipAndFlushesBeforeSnapshot() {
        var series = weekly();
        when(profiles.view(owner)).thenReturn(com.projectardor.platform.identity.profile.service.ProfileView.from(UserProfile.create(owner, "Fixture")));
        when(rules.findOwnedForUpdate(series.getId(), owner)).thenReturn(Optional.of(series));
        when(tasks.findSeriesForUpdate(series.getId(), owner)).thenReturn(List.of());
        var result = service.updateDetailsView(owner, series.getId(), "Revised", null, null, null, 4);
        assertThat(result.series().id()).isEqualTo(series.getId());
        assertThat(result.series().title()).isEqualTo("Revised");
        assertThat(result.updatedOccurrences()).isZero();
        assertThat(result.preservedOccurrences()).isZero();
        verify(rules).flush(); verify(tasks).flush();
        assertThatThrownBy(() -> service.updateDetailsView(UUID.randomUUID(), series.getId(), "Foreign", null, null, null, 4))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(tasks, never()).deleteAll(any());
    }

    @Test void creationSnapshotsAfterPersistenceAndDoesNotExpandAgainForTheView() {
        when(profiles.view(owner)).thenReturn(com.projectardor.platform.identity.profile.service.ProfileView.from(UserProfile.create(owner, "Fixture")));
        var saved = new AtomicReference<TaskSeries>();
        when(rules.save(any(TaskSeries.class))).thenAnswer(call -> {saved.set(call.getArgument(0)); return saved.get();});
        Instant created = Instant.parse("2026-10-21T00:00:00Z");
        doAnswer(call -> {ReflectionTestUtils.setField(saved.get(), "createdAt", created); return null;}).when(rules).flush();
        var view = service.createView(owner, "Meeting", null, CalendarTaskPriority.MEDIUM, CalendarTaskSource.AGENT,
                RecurrenceFrequency.DAILY, 1, Set.of(), null, LocalTime.of(19, 0), start, null, 2, 4);
        assertThat(view.createdAt()).isEqualTo(created);
        assertThat(view.weekdays()).isEmpty();
        verify(tasks, times(2)).save(any(CalendarTask.class));
        verify(tasks).flush();
    }

    private TaskSeries weekly() {
        return TaskSeries.create(owner, "Meeting", null, CalendarTaskPriority.MEDIUM, CalendarTaskSource.AGENT,
                ZoneId.of("Asia/Shanghai"), RecurrenceFrequency.WEEKLY, 1,
                Set.of(DayOfWeek.THURSDAY, DayOfWeek.MONDAY), null, LocalTime.of(19, 0), start, null, 4);
    }
}
