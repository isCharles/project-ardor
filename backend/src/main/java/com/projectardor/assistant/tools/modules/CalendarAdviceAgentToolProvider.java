package com.projectardor.assistant.tools.modules;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.projectardor.scheduling.advice.service.CalendarAdviceService;
import com.projectardor.scheduling.advice.service.CalendarAdviceView;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** Scheduling advice adaptation; no calendar entity ownership or permission policy lives here. */
@Component
public final class CalendarAdviceAgentToolProvider implements AgentToolProvider {
    private final CalendarAdviceService service;
    public CalendarAdviceAgentToolProvider(CalendarAdviceService service) { this.service = service; }
    @Override public Bound bind(AgentToolContext context) { return new Bound(service, context); }
    @Override public List<Class<?>> toolTypes() { return List.of(Bound.class); }

    public static final class Bound {
        private final CalendarAdviceService service;
        private final AgentToolContext context;
        private Bound(CalendarAdviceService service, AgentToolContext context) {
            this.service = service;
            this.context = context;
        }

        @Tool(name = "get_calendar_advice", value = "读取当前用户日历右侧的 Ardor 建议卡；需要知道上次给用户的规划时先调用")
        public CalendarAdviceView getCalendarAdvice() {
            return service.get(context.userId());
        }

        @Tool(name = "update_calendar_advice", value = """
                把一次规划建议写入日历右侧的建议卡，让用户在日历里持续看到，而不是只在聊天里说过。
                当用户让你“规划一下接下来几天/几周/几个月”“看看求职节奏”“给出接下来做什么的建议”，或你主动给出这类时间范围建议时，必须先调用本工具再回答。
                只写真实基于用户日历、面试、投递、学习状态的建议；不要编造安排。
                必须精炼：headline 是一句话结论；days/weeks/months 各最多 3 条，每条一句话，不要写大段段落或 Markdown 标题。
                """)
        public CalendarAdviceView updateCalendarAdvice(
                @P(value = "一句话结论，最多 60 字；没有就传空字符串", required = false) String headline,
                @P(value = "未来几天的行动，最多 3 条，每件一句话；没有就传空数组", required = false) List<String> days,
                @P(value = "未来 2–4 周的行动，最多 3 条，每件一句话；没有就传空数组", required = false) List<String> weeks,
                @P(value = "未来 1–3 个月的方向，最多 3 条，每件一句话；没有就传空数组", required = false) List<String> months) {
            return service.save(context.userId(), headline, days, weeks, months);
        }

        @Tool(name = "delete_calendar_advice", value = "提议清空当前用户日历右侧的 Ardor 建议卡；只在用户明确要求清除建议时使用，需用户点击确认")
        public Map<String, Object> deleteCalendarAdvice() {
            return context.proposals().propose("calendar_advice", null, "日历建议",
                    "清除日历右侧的 Ardor 建议卡，不影响任何日程", "/api/calendar/advice");
        }


    }
}
