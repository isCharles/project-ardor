package com.projectardor.jobsearch.applicationactivity.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.projectardor.jobsearch.applicationactivity.web.ApplicationRhythmResponse;
import tools.jackson.databind.json.JsonMapper;

class ApplicationRhythmViewTests {
    @Test void exactLegacyProjectionAndNestedCountFieldsArePreserved() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        var view = new ApplicationRhythmView(monday, monday, 40, 40, 100, true, LocalTime.of(19, 0), true, false,
                List.of(new ApplicationRhythmView.DayCount(monday, 40), new ApplicationRhythmView.DayCount(monday.plusDays(1), 0)));
        var legacy = new ApplicationRhythmResponse(monday, monday, 40, 40, 100, true, LocalTime.of(19, 0), true, false,
                List.of(new ApplicationRhythmResponse.DayCount(monday, 40), new ApplicationRhythmResponse.DayCount(monday.plusDays(1), 0)));
        var mapper = JsonMapper.builder().build();
        String expected = mapper.writeValueAsString(legacy);
        assertThat(mapper.writeValueAsString(view)).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(ApplicationRhythmResponse.from(view))).isEqualTo(expected);
        assertThat(mapper.readTree(expected).propertyNames()).containsExactly("today", "weekStart", "todayCount", "weeklyCount",
                "weeklyGoal", "reminderEnabled", "reminderTime", "weekdaysOnly", "reminderDue", "days", "advice");
        assertThat(mapper.readTree(expected).path("days").get(0).propertyNames()).containsExactly("date", "count");
    }

    @Test void snapshotCannotAliasOrExposeMutableDailyCounts() {
        var days = new ArrayList<ApplicationRhythmView.DayCount>();
        days.add(new ApplicationRhythmView.DayCount(LocalDate.of(2026, 10, 19), 40));
        var view = new ApplicationRhythmView(null, null, 40, 40, 100, false, null, true, false, days);
        days.clear();
        assertThat(view.days()).hasSize(1);
        assertThatThrownBy(() -> view.days().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
