package com.projectardor.assistant.tools.modules;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.projectardor.scheduling.domain.CalendarTaskPriority;
import com.projectardor.scheduling.domain.CalendarTaskSource;
import com.projectardor.scheduling.domain.RecurrenceFrequency;
import com.projectardor.scheduling.service.CalendarTaskService;
import com.projectardor.scheduling.service.CalendarTaskView;
import com.projectardor.scheduling.service.TaskSeriesService;
import com.projectardor.scheduling.service.TaskSeriesView;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** Scheduling tool adaptation; ownership and mutations remain in scheduling application services. */
@Component
public final class SchedulingAgentToolProvider implements AgentToolProvider {
    private final CalendarTaskService calendarTaskService;
    private final TaskSeriesService taskSeriesService;
    public SchedulingAgentToolProvider(CalendarTaskService calendarTaskService, TaskSeriesService taskSeriesService) {
        this.calendarTaskService = calendarTaskService;
        this.taskSeriesService = taskSeriesService;
    }
    @Override public Bound bind(AgentToolContext context) { return new Bound(calendarTaskService, taskSeriesService, context); }
    @Override public List<Class<?>> toolTypes() { return List.of(Bound.class); }

    public static final class Bound {
        private final CalendarTaskService calendarTaskService;
        private final TaskSeriesService taskSeriesService;
        private final AgentToolContext context;
        private Bound(CalendarTaskService calendarTaskService, TaskSeriesService taskSeriesService, AgentToolContext context) {
            this.calendarTaskService = calendarTaskService;
            this.taskSeriesService = taskSeriesService;
            this.context = context;
        }

        @Tool(name = "list_calendar_tasks", value = "读取当前用户的日历待办。可按 ISO-8601 时间范围过滤；不传范围则返回全部待办。seriesId 不为空的是重复安排的实例（如每周组会、学校固定课程），属于固定承诺，不是需要补勾的逾期待办；不要把它和 Ardor 的“课程（学习计划）”混淆；只有 seriesId 为空的一次性待办才可能逾期")
        public List<CalendarTaskView> listCalendarTasks(
                @P(value = "开始时间，ISO-8601 格式；不限制时传空字符串", required = false) String from,
                @P(value = "结束时间，ISO-8601 格式；不限制时传空字符串", required = false) String to) {
            return calendarTaskService.listViews(context.userId(), nullableInstant(from), nullableInstant(to));
        }

        @Tool(name = "find_available_slots", value = """
                在给定时间窗口内查找能放下指定时长的空闲区间，由服务端按当前用户的真实日程计算，不要自己心算时段。
                日程只保存一个时间点，因此既有事项按 assumed_event_minutes 估算占用；返回的 freeSlots 是连续可用窗口，busy 是挡住这些窗口的事项。
                提议任何具体新时段（复习、模拟面试、面试准备）之前必须先调用本工具；seriesId 不为空的固定承诺（组会、学校课程）不可移动，只能避开。
                """)
        public com.projectardor.scheduling.service.CalendarAvailabilityTypes.Window findAvailableSlots(
                @P("窗口开始时间，带时区的 ISO-8601") String from,
                @P("窗口结束时间，带时区的 ISO-8601，窗口最多 31 天") String to,
                @P("需要放下的时长，单位分钟，例如 60") int slotMinutes,
                @P(value = "既有事项按多少分钟估算占用；不确定就传 60", required = false) Integer assumedEventMinutes) {
            return calendarTaskService.findFreeSlots(context.userId(), nullableInstant(from), nullableInstant(to),
                    slotMinutes, assumedEventMinutes == null || assumedEventMinutes <= 0 ? 60 : assumedEventMinutes, null);
        }

        @Tool(name = "check_calendar_conflict", value = """
                检查候选时间点（含时长）是否与当前用户既有日程冲突，返回撞上的事项；空数组才表示这段时间没有冲突。
                给出替代时段或改期建议之前必须调用，不得凭对话记忆推算，也不得把用户说过的“不可调整”当成已核对过冲突的证据。
                """)
        public List<com.projectardor.scheduling.service.CalendarAvailabilityTypes.BusyItem> checkCalendarConflict(
                @P("候选开始时间，带时区的 ISO-8601") String dueAt,
                @P("候选时长，单位分钟") int durationMinutes,
                @P(value = "既有事项按多少分钟估算占用；不确定就传 60", required = false) Integer assumedEventMinutes,
                @P(value = "修改既有日程时传它的 UUID，避免自己和自己冲突；不是修改传空字符串", required = false) String excludeTaskId) {
            return calendarTaskService.conflictsFor(context.userId(), nullableInstant(dueAt), durationMinutes,
                    assumedEventMinutes == null || assumedEventMinutes <= 0 ? 60 : assumedEventMinutes,
                    nullableUuid(excludeTaskId, "日程 ID"));
        }

        @Tool(name = "create_calendar_task", value = "为当前用户创建任意明确的日程或待办，不限于求职事项。用户说出自然语言安排，或粘贴含日期时间的通知时使用；日期确有歧义时必须先追问，不得猜测。写入带时间的日程前，先调用 check_calendar_conflict 确认该时段不与既有事项冲突。若这条日程属于某场真实面试（面试时间或准备任务），传入该面试流程 UUID")
        public CalendarTaskView createCalendarTask(
                @P("日历格中的极短标题，优先使用公司或对象加事项，通常 2 到 8 个中文字符，例如‘字节面试’‘完善简历’；不得照抄冗长岗位名称") String title,
                @P(value = "完整说明。岗位全称、业务线、会议方式、准备材料和来源摘要都放在这里，不要塞进短标题；没有时传空字符串", required = false) String description,
                @P(value = "到期或发生时间，必须是带时区的 ISO-8601；无截止时间的普通任务可传空字符串", required = false) String dueAt,
                @P(value = "优先级：LOW、MEDIUM 或 HIGH", required = false) String priority,
                @P(value = "关联的真实面试流程 UUID；不关联传空字符串", required = false) String jobOpportunityId) {
            return calendarTaskService.createViewOnce(
                    context.userId(),
                    context.actionId(),
                    context.runId(),
                    title,
                    description,
                    nullableInstant(dueAt),
                    parsePriority(priority),
                    CalendarTaskSource.AGENT,
                    nullableUuid(jobOpportunityId, "面试流程 ID"));
        }

        @Tool(name = "delete_calendar_task", value = "永久删除当前用户指定的一条日历待办。必须先调用 list_calendar_tasks 获取真实 UUID；只有用户明确要求删除或取消该待办时使用，目标不唯一时必须先追问")
        public Map<String, Object> deleteCalendarTask(@P("日历待办 UUID") String taskId) {
            UUID id = uuid(taskId, "日历待办 ID");
            CalendarTaskView task = calendarTaskService.detail(context.userId(), id);
            return context.proposals().propose("calendar_task", id, task.title(),
                    task.dueAt() == null ? "未安排时间" : task.dueAt().toString(),
                    "/api/calendar/tasks/" + id);
        }

        @Tool(name = "update_calendar_task", value = "修改当前用户普通日程的标题、说明、时间、优先级和状态，保留 UUID；先列表读取并保留未要求改动的字段。学习、记忆卡、复测日程请改对应业务模块，不通过此工具强行改写")
        public CalendarTaskView updateCalendarTask(@P("日程 UUID") String taskId, @P("完整短标题") String title,
                @P("完整说明，清空传空字符串") String description, @P("完整 ISO-8601 带时区时间；无截止时间传空字符串") String dueAt,
                @P("完整优先级：LOW、MEDIUM、HIGH") String priority, @P("完整状态：TODO、IN_PROGRESS、COMPLETED、CANCELLED") String status) {
            return calendarTaskService.updateGeneralView(context.userId(), uuid(taskId, "日程 ID"), title, description,
                    nullableInstant(dueAt), parsePriority(priority), enumValue(com.projectardor.scheduling.domain.CalendarTaskStatus.class, status, null));
        }

        @Tool(name = "list_recurring_tasks", value = "读取当前用户的重复安排（每天、每周、每月循环的日程规则）及其接下来几次的日期。用户问“我有哪些固定安排”，或需要修改、取消某条重复安排前定位对象时使用")
        public List<TaskSeriesView> listRecurringTasks() {
            return taskSeriesService.listViews(context.userId(), 4);
        }

        @Tool(name = "update_recurring_task", value = "修改当前用户重复安排的时间、标题、说明或优先级，保留规则和实例 UUID。先读取规则，只更新未被用户单独修改且尚未完成的未来实例；返回更新和保留数量，不会新建第二套规则。此工具不修改重复频率、日期范围或时区")
        public Map<String, Object> updateRecurringTask(@P("重复规则 UUID") String seriesId,
                @P(value = "新标题，不修改传 null", required = false) String title,
                @P(value = "新说明，不修改传 null，清空传空字符串", required = false) String description,
                @P(value = "LOW、MEDIUM 或 HIGH，不修改传 null", required = false) String priority,
                @P(value = "新本地时间 HH:mm，不修改传 null", required = false) String timeOfDay) {
            var result = taskSeriesService.updateDetailsView(context.userId(), uuid(seriesId, "重复规则 ID"), title, description,
                    priority == null ? null : parsePriority(priority), timeOfDay == null ? null : LocalTime.parse(timeOfDay), 4);
            return Map.of("series", result.series(),
                    "updatedOccurrences", result.updatedOccurrences(), "preservedOccurrences", result.preservedOccurrences());
        }

        @Tool(name = "create_recurring_task", value = """
                创建一条按天、按周或按月循环的日程规则，并把接下来一个季度的每一次都写进日历。
                用户说“每周四晚上组会”“每天早上读书半小时”“每月 15 号复盘”这类固定安排时使用，不要改用 create_calendar_task 逐条创建。
                只发生一次的事情必须用 create_calendar_task。频率、星期几或时间不明确时先追问，不得猜测。
                """)
        public TaskSeriesView createRecurringTask(
                @P("日历格中的极短标题，通常 2 到 8 个中文字符，例如‘组会’‘读书’‘周报’；完整说明写进 description") String title,
                @P(value = "完整说明：地点、会议方式、要准备什么。没有时传空字符串", required = false) String description,
                @P("重复频率：DAILY 表示按天，WEEKLY 表示按周，MONTHLY 表示按月") String frequency,
                @P(value = "间隔几个周期，默认 1。例如每两周一次传 2", required = false) Integer interval,
                @P(value = "按周重复时的星期几，逗号分隔，例如 THU 或 MON,WED,FRI；其他频率必须传空字符串", required = false) String weekdays,
                @P(value = "按月重复时的日期（1-31）；其他频率传空", required = false) Integer monthDay,
                @P(value = "每次发生的本地时间，24 小时制 HH:mm，例如 19:00；不传默认 09:00", required = false) String timeOfDay,
                @P(value = "从哪一天开始，格式 YYYY-MM-DD；不传表示从今天开始", required = false) String startDate,
                @P(value = "重复到哪一天为止，格式 YYYY-MM-DD；长期有效时传空字符串", required = false) String untilDate,
                @P(value = "总共重复多少次；与结束日期只能二选一，长期有效时传空", required = false) Integer occurrenceLimit,
                @P(value = "优先级：LOW、MEDIUM 或 HIGH", required = false) String priority) {
            return taskSeriesService.createView(
                    context.userId(),
                    title,
                    blankToNull(description),
                    parsePriority(priority),
                    CalendarTaskSource.AGENT,
                    parseFrequency(frequency),
                    interval,
                    parseWeekdays(weekdays),
                    monthDay,
                    parseLocalTime(timeOfDay),
                    parseLocalDate(startDate, "开始日期"),
                    parseLocalDate(untilDate, "结束日期"),
                    occurrenceLimit, 4);
        }

        @Tool(name = "delete_recurring_task", value = "停止一条重复安排，并撤回它尚未开始、也没被用户动过的后续日程；已经发生或已完成的记录会保留。必须先调用 list_recurring_tasks 获取真实 UUID；只有用户明确要求取消该固定安排时使用，目标不唯一时必须先追问")
        public Map<String, Object> deleteRecurringTask(@P("重复安排 UUID") String seriesId) {
            UUID id = uuid(seriesId, "重复安排 ID");
            TaskSeriesView series = taskSeriesService.metadata(context.userId(), id);
            return context.proposals().propose("task_series", id, series.title() + "（" + series.summary() + "）",
                    "撤回尚未开始、也没被动过的后续日程；已发生和已完成的记录保留",
                    "/api/calendar/series/" + id);
        }


        private UUID uuid(String value, String label) {
            try {
                return UUID.fromString(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(label + " 格式不正确");
            }
        }

        private UUID nullableUuid(String value, String label) {
            return value == null || value.isBlank() ? null : uuid(value, label);
        }

        private String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }

        private Instant nullableInstant(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return OffsetDateTime.parse(value.strip()).toInstant();
            } catch (RuntimeException ignored) {
                try {
                    return Instant.parse(value.strip());
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("时间必须使用带时区的 ISO-8601 格式，例如 2026-09-10T14:00:00+08:00");
                }
            }
        }

        private RecurrenceFrequency parseFrequency(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("重复频率必须是 DAILY、WEEKLY 或 MONTHLY");
            String token = value.strip().toUpperCase(Locale.ROOT);
            return switch (token) {
                case "DAILY", "DAY", "每天", "每日" -> RecurrenceFrequency.DAILY;
                case "WEEKLY", "WEEK", "每周" -> RecurrenceFrequency.WEEKLY;
                case "MONTHLY", "MONTH", "每月" -> RecurrenceFrequency.MONTHLY;
                default -> throw new IllegalArgumentException("重复频率必须是 DAILY、WEEKLY 或 MONTHLY");
            };
        }

        private Set<DayOfWeek> parseWeekdays(String value) {
            if (value == null || value.isBlank()) return Set.of();
            Set<DayOfWeek> days = new LinkedHashSet<>();
            for (String token : value.split("[,，、\\s]+")) {
                if (!token.isBlank()) days.add(TaskSeriesService.parseWeekday(token));
            }
            return days;
        }

        private LocalTime parseLocalTime(String value) {
            if (value == null || value.isBlank()) return null;
            String text = value.strip();
            if (text.matches("\\d:\\d{2}")) text = "0" + text;
            try {
                return LocalTime.parse(text);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("时间必须是 24 小时制的 HH:mm，例如 19:00");
            }
        }

        private LocalDate parseLocalDate(String value, String label) {
            if (value == null || value.isBlank()) return null;
            try {
                return LocalDate.parse(value.strip());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(label + "必须是 YYYY-MM-DD 格式");
            }
        }

        private CalendarTaskPriority parsePriority(String value) {
            if (value == null || value.isBlank()) return CalendarTaskPriority.MEDIUM;
            try {
                return CalendarTaskPriority.valueOf(value.strip().toUpperCase());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("优先级必须是 LOW、MEDIUM 或 HIGH");
            }
        }

        private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
            if (value == null || value.isBlank()) return fallback;
            try { return Enum.valueOf(type, value.strip().toUpperCase()); }
            catch (RuntimeException exception) { throw new IllegalArgumentException("不支持的类型：" + value); }
        }
    }
}
