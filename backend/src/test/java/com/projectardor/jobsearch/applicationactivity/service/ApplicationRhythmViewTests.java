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
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);

    @Test void separateCountsKeepAnUnrecordedResumeCountAsNullInTheHttpProjection() {
        var days = List.of(new ApplicationRhythmView.DayCount(MONDAY, 89, null),
                new ApplicationRhythmView.DayCount(MONDAY.plusDays(1), 12, 5));
        var view = new ApplicationRhythmView(MONDAY, MONDAY, 89, null, 101, 5, 2, 1, 20, 30,
                true, LocalTime.of(19, 0), true, false, days, "fixture");
        var mapper = JsonMapper.builder().build();
        var json = mapper.readTree(mapper.writeValueAsString(ApplicationRhythmResponse.from(view)));

        assertThat(json.propertyNames()).containsExactly("today", "weekStart", "todayGreetingCount",
                "todayResumeCount", "weeklyGreetingCount", "weeklyResumeCount", "weeklyGreetingRecordedDays",
                "weeklyResumeRecordedDays", "weeklyResumeGoal", "weeklyGreetingGoal", "reminderEnabled",
                "reminderTime", "weekdaysOnly", "reminderDue", "days", "advice");
        assertThat(json.path("todayGreetingCount").asInt()).isEqualTo(89);
        assertThat(json.path("todayResumeCount").isNull()).as("未记录 must stay null, never 0").isTrue();
        assertThat(json.path("weeklyResumeRecordedDays").asInt()).isEqualTo(1);
        assertThat(json.path("days").get(0).propertyNames()).containsExactly("date", "greetingCount", "resumeCount");
        assertThat(json.path("days").get(0).path("resumeCount").isNull()).isTrue();
        assertThat(json.path("days").get(1).path("greetingCount").asInt()).isEqualTo(12);
        assertThat(json.path("days").get(1).path("resumeCount").asInt()).isEqualTo(5);
        assertThat(mapper.writeValueAsString(ApplicationRhythmResponse.from(view)))
                .isEqualTo(mapper.writeValueAsString(view));
    }

    @Test void snapshotCannotAliasOrExposeMutableDailyCounts() {
        var days = new ArrayList<ApplicationRhythmView.DayCount>();
        days.add(new ApplicationRhythmView.DayCount(MONDAY, 40, null));
        var view = new ApplicationRhythmView(null, null, 40, null, 40, 0, 1, 0, 100, null,
                false, null, true, false, days, null);
        days.clear();
        assertThat(view.days()).hasSize(1);
        assertThatThrownBy(() -> view.days().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
