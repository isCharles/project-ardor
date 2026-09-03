package com.projectardor.calendar.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.calendar.domain.CalendarTask;
import com.projectardor.calendar.domain.CalendarTaskPriority;
import com.projectardor.calendar.domain.CalendarTaskSource;
import com.projectardor.calendar.domain.CalendarTaskStatus;
import com.projectardor.calendar.domain.RecurrenceFrequency;
import com.projectardor.calendar.domain.TaskSeries;
import com.projectardor.calendar.domain.TaskSeriesStatus;
import com.projectardor.calendar.repository.CalendarTaskRepository;
import com.projectardor.calendar.repository.TaskSeriesRepository;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.profile.service.ProfileService;

/**
 * Repeating arrangements: the rule is stored once, the calendar keeps getting
 * real rows.
 *
 * <p>Occurrences are materialized a horizon ahead rather than expanded at read
 * time, because a calendar entry has state — completed, edited, deleted — that a
 * virtual row could not carry. A scheduled pass keeps the horizon rolling
 * forward so a weekly meeting never runs out.
 */
@Service
public class TaskSeriesService {

    /** How far ahead the calendar is filled in. Roughly a quarter. */
    static final int HORIZON_DAYS = 120;

    private final TaskSeriesRepository seriesRepository;
    private final CalendarTaskRepository taskRepository;
    private final ProfileService profileService;

    public TaskSeriesService(
            TaskSeriesRepository seriesRepository,
            CalendarTaskRepository taskRepository,
            ProfileService profileService) {
        this.seriesRepository = seriesRepository;
        this.taskRepository = taskRepository;
        this.profileService = profileService;
    }

    public record SeriesCreation(TaskSeries series, List<LocalDate> created) {}

    @Transactional
    public SeriesCreation create(
            UUID userId,
            String title,
            String description,
            CalendarTaskPriority priority,
            CalendarTaskSource source,
            RecurrenceFrequency frequency,
            Integer interval,
            Set<DayOfWeek> weekdays,
            Integer monthDay,
            LocalTime timeOfDay,
            LocalDate startDate,
            LocalDate untilDate,
            Integer occurrenceLimit) {
        if (frequency == null) throw new IllegalArgumentException("重复频率必须是 DAILY、WEEKLY 或 MONTHLY");
        int intervalValue = interval == null ? 1 : interval;
        if (intervalValue < 1 || intervalValue > 52) throw new IllegalArgumentException("重复间隔必须在 1 到 52 之间");
        if (occurrenceLimit != null && (occurrenceLimit < 1 || occurrenceLimit > 500)) {
            throw new IllegalArgumentException("重复次数必须在 1 到 500 之间");
        }
        if (monthDay != null && (monthDay < 1 || monthDay > 31)) {
            throw new IllegalArgumentException("每月重复的日期必须在 1 到 31 之间");
        }
        if (frequency != RecurrenceFrequency.WEEKLY && weekdays != null && !weekdays.isEmpty()) {
            throw new IllegalArgumentException("只有按周重复才能指定星期几");
        }
        if (frequency != RecurrenceFrequency.MONTHLY && monthDay != null) {
            throw new IllegalArgumentException("只有按月重复才能指定每月第几天");
        }

        ZoneId zone = zoneOf(userId);
        LocalDate today = LocalDate.now(zone);
        LocalDate start = startDate == null ? today : startDate;
        if (start.isBefore(today)) {
            throw new IllegalArgumentException("重复安排的开始日期不能早于今天");
        }
        if (untilDate != null && untilDate.isBefore(start)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        if (untilDate != null && occurrenceLimit != null) {
            throw new IllegalArgumentException("结束日期和重复次数只能二选一");
        }

        TaskSeries series = TaskSeries.create(
                userId,
                requiredTitle(title),
                nullableText(description),
                priority,
                source,
                zone,
                frequency,
                intervalValue,
                normalizeWeekdays(frequency, weekdays, start),
                monthDay,
                timeOfDay == null ? LocalTime.of(9, 0) : timeOfDay.withSecond(0).withNano(0),
                start,
                untilDate,
                occurrenceLimit);

        seriesRepository.save(series);
        List<LocalDate> created = materialize(series, LocalDate.now(zone));
        if (created.isEmpty() && series.getStatus() == TaskSeriesStatus.ENDED) {
            throw new IllegalArgumentException("这条重复规则不会产生任何日程，请检查开始日期和结束条件");
        }
        return new SeriesCreation(series, created);
    }

    @Transactional(readOnly = true)
    public List<TaskSeries> list(UUID userId) {
        return seriesRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<TaskSeries> listActive(UUID userId) {
        return seriesRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(userId, TaskSeriesStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public TaskSeries get(UUID userId, UUID seriesId) {
        return seriesRepository.findByIdAndUserId(seriesId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("重复安排不存在"));
    }

    /** The dates still on the calendar for this series, soonest first. */
    @Transactional(readOnly = true)
    public List<LocalDate> upcoming(UUID userId, UUID seriesId, int max) {
        LocalDate today = LocalDate.now(zoneOf(userId));
        return taskRepository.findAllBySeriesIdAndUserId(seriesId, userId).stream()
                .map(CalendarTask::getOccurrenceDate)
                .filter(date -> date != null && !date.isBefore(today))
                .sorted()
                .limit(max)
                .toList();
    }

    /**
     * Stops a series. What has already happened, and anything the user has
     * already touched, stays on the calendar; only untouched future
     * occurrences are withdrawn. The rule itself is kept as history so a past
     * occurrence can still say where it came from.
     */
    @Transactional
    public int cancel(UUID userId, UUID seriesId) {
        TaskSeries series = get(userId, seriesId);
        Instant now = Instant.now();
        List<CalendarTask> withdrawn = taskRepository.findAllBySeriesIdAndUserId(seriesId, userId).stream()
                .filter(task -> task.getStatus() == CalendarTaskStatus.TODO)
                .filter(task -> !task.isUserModified())
                .filter(task -> task.getDueAt() != null && task.getDueAt().isAfter(now))
                .toList();
        taskRepository.deleteAll(withdrawn);
        series.cancel();
        return withdrawn.size();
    }

    /** Rolls the horizon forward for the series that have fallen behind it. */
    @Transactional
    public int extendActiveSeries(int maxSeries) {
        LocalDate today = LocalDate.now();
        List<TaskSeries> pending = seriesRepository
                .findAllByStatusAndMaterializedThroughLessThanOrderByMaterializedThroughAsc(
                        TaskSeriesStatus.ACTIVE, today.plusDays(HORIZON_DAYS), Limit.of(maxSeries));
        int created = 0;
        for (TaskSeries series : pending) {
            created += materialize(series, LocalDate.now(ZoneId.of(series.getTimezone()))).size();
        }
        return created;
    }

    /* ---------------------------------------------------------------
       Expansion into real calendar rows.
       --------------------------------------------------------------- */

    private List<LocalDate> materialize(TaskSeries series, LocalDate today) {
        LocalDate horizon = today.plusDays(HORIZON_DAYS);
        Set<LocalDate> existing = taskRepository.findOccurrenceDates(series.getId());
        List<LocalDate> created = new ArrayList<>();
        for (LocalDate date : series.occurrencesThrough(horizon)) {
            if (existing.contains(date)) continue;
            taskRepository.save(CalendarTask.createOccurrence(series, date));
            created.add(date);
        }
        series.markMaterializedThrough(horizon);
        if (series.isExhaustedThrough(horizon)) series.end();
        return created;
    }

    private ZoneId zoneOf(UUID userId) {
        return ZoneId.of(profileService.get(userId).getTimezone());
    }

    private Set<DayOfWeek> normalizeWeekdays(
            RecurrenceFrequency frequency, Set<DayOfWeek> weekdays, LocalDate start) {
        if (frequency != RecurrenceFrequency.WEEKLY) return Set.of();
        if (weekdays == null || weekdays.isEmpty()) return EnumSet.of(start.getDayOfWeek());
        return weekdays;
    }

    private String requiredTitle(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("重复安排的标题不能为空");
        String normalized = value.strip();
        if (normalized.length() > 240) throw new IllegalArgumentException("重复安排的标题不能超过 240 个字符");
        return normalized;
    }

    private String nullableText(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > 4000) throw new IllegalArgumentException("重复安排的说明过长");
        return normalized;
    }

    /** Accepts MON/MONDAY/周一/星期四/1, because the caller may be a model. */
    public static DayOfWeek parseWeekday(String value) {
        String token = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        return switch (token) {
            case "MON", "MONDAY", "1", "周一", "星期一", "礼拜一" -> DayOfWeek.MONDAY;
            case "TUE", "TUES", "TUESDAY", "2", "周二", "星期二", "礼拜二" -> DayOfWeek.TUESDAY;
            case "WED", "WEDNESDAY", "3", "周三", "星期三", "礼拜三" -> DayOfWeek.WEDNESDAY;
            case "THU", "THUR", "THURS", "THURSDAY", "4", "周四", "星期四", "礼拜四" -> DayOfWeek.THURSDAY;
            case "FRI", "FRIDAY", "5", "周五", "星期五", "礼拜五" -> DayOfWeek.FRIDAY;
            case "SAT", "SATURDAY", "6", "周六", "星期六", "礼拜六" -> DayOfWeek.SATURDAY;
            case "SUN", "SUNDAY", "7", "0", "周日", "周天", "星期日", "星期天", "礼拜日", "礼拜天" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("无法识别的星期：" + value);
        };
    }
}
