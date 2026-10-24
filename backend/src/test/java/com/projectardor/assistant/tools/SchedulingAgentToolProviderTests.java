package com.projectardor.assistant.tools;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.tools.modules.AgentToolContext;
import com.projectardor.assistant.tools.modules.SchedulingAgentToolProvider;
import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.domain.CalendarTaskStatus;
import com.projectardor.scheduling.domain.RecurrenceFrequency;
import com.projectardor.scheduling.service.CalendarTaskService;
import com.projectardor.scheduling.service.TaskSeriesService;

class SchedulingAgentToolProviderTests {
    private final UUID owner = UUID.randomUUID();
    private final CalendarTaskService calendar = mock(CalendarTaskService.class);
    private final TaskSeriesService series = mock(TaskSeriesService.class);
    private final SchedulingAgentToolProvider.Bound bound = new SchedulingAgentToolProvider(calendar, series)
            .bind(new AgentToolContext(owner, (kind, id, label, detail, endpoint) -> Map.of(),
                    (source, content) -> "unused"));

    @Test
    void ordinaryTaskParsingKeepsOffsetIdentityAndExistingNullDefaults() {
        UUID interview = UUID.randomUUID(), task = UUID.randomUUID();
        bound.createCalendarTask("Review", "", " 2028-04-01T09:00:00+08:00 ", " high ", interview.toString());
        verify(calendar).createView(owner, "Review", "", Instant.parse("2028-04-01T01:00:00Z"),
                CalendarTaskPriority.HIGH, CalendarTaskSource.AGENT, interview);
        bound.updateCalendarTask(task.toString(), "Revised", "", "", "low", "completed");
        verify(calendar).updateGeneralView(owner, task, "Revised", "", null, CalendarTaskPriority.LOW,
                CalendarTaskStatus.COMPLETED);
        bound.listCalendarTasks(null, " ");
        verify(calendar).listViews(owner, null, null);
    }

    @Test
    void recurrenceParsingPreservesAliasesWeekdayDedupAndDelegatedDefaults() {
        bound.createRecurringTask("Meeting", "  Details  ", "每周", null, "MON，周四 MON", null,
                "9:05", "2028-04-01", "", null, "");
        verify(series).createView(owner, "Meeting", "Details", CalendarTaskPriority.MEDIUM, CalendarTaskSource.AGENT,
                RecurrenceFrequency.WEEKLY, null, Set.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), null,
                LocalTime.of(9, 5), LocalDate.of(2028, 4, 1), null, null, 4);
        bound.createRecurringTask("Daily", " ", "day", null, "", null, "", "", null, null, null);
        verify(series).createView(owner, "Daily", null, CalendarTaskPriority.MEDIUM, CalendarTaskSource.AGENT,
                RecurrenceFrequency.DAILY, null, Set.of(), null, null, null, null, null, 4);
        bound.createRecurringTask("Monthly", null, "MONTH", 2, null, 15, "10:00", null, null, 3, "LOW");
        verify(series).createView(owner, "Monthly", null, CalendarTaskPriority.LOW, CalendarTaskSource.AGENT,
                RecurrenceFrequency.MONTHLY, 2, Set.of(), 15, LocalTime.of(10, 0), null, null, 3, 4);
    }

    @Test
    void malformedParametersFailBeforeApplicationCallsWithLegacyErrors() {
        assertThatThrownBy(() -> bound.createCalendarTask("Task", null, "2028-04-01T09:00:00", null, null))
                .hasMessageContaining("带时区的 ISO-8601");
        assertThatThrownBy(() -> bound.createCalendarTask("Task", null, null, null, "wrong-id"))
                .hasMessage("面试流程 ID 格式不正确");
        assertThatThrownBy(() -> bound.createRecurringTask("Task", null, "YEARLY", null, null, null,
                null, null, null, null, null)).hasMessageContaining("重复频率必须是");
        assertThatThrownBy(() -> bound.createRecurringTask("Task", null, "DAILY", null, null, null,
                "25:00", null, null, null, null)).hasMessageContaining("24 小时制");
        assertThatThrownBy(() -> bound.createRecurringTask("Task", null, "DAILY", null, null, null,
                null, "2028-02-30", null, null, null)).hasMessageContaining("开始日期必须是");
        verifyNoInteractions(calendar, series);
    }
}
