package com.projectardor.assistant.tools.modules;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.assistant.automation.service.AutomationService;
import com.projectardor.assistant.automation.service.AutomationView;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** Automation adaptation only; authorization and scheduling stay in application services. */
@Component
public final class AutomationAgentToolProvider implements AgentToolProvider {
    private final AutomationService service;
    public AutomationAgentToolProvider(AutomationService service) { this.service = service; }
    @Override public Bound bind(AgentToolContext context) { return new Bound(service, context); }
    @Override public List<Class<?>> toolTypes() { return List.of(Bound.class); }

    public static final class Bound {
        private final AutomationService service;
        private final AgentToolContext context;
        private Bound(AutomationService service, AgentToolContext context) {
            this.service = service;
            this.context = context;
        }

        @Tool(name = "list_automations", value = "读取当前用户的定时自动化任务：名称、执行时间、是否启用、上次运行状态。用户问“我有哪些自动化/定时任务”或要修改、删除前先调用")
        public List<AutomationView> listAutomations() {
            return service.listViews(context.userId());
        }

        @Tool(name = "get_automation", value = "读取一条定时自动化任务的完整配置。修改或删除前先用 list_automations 或本工具确认 UUID")
        public AutomationView getAutomation(@P("自动化任务 UUID") String automationId) {
            return service.getView(context.userId(), uuid(automationId, "自动化任务 ID"));
        }

        @Tool(name = "create_automation", value = """
                创建一条定时自动化任务：按 cron 在后台把 prompt 发给 Ardor，像用户正常发消息一样执行，结果作为 Ardor 的回复出现；触发消息本身不显示。
                用户说“每天/每周/每月/定期”并明确要做的事时使用。执行时间必须转成 6 段 cron（秒 分 时 日 月 周），例如每天 09:00 是 0 0 9 * * *，每周一 09:30 是 0 30 9 * * 1。
                时间或任务不明确时先追问；不要把一次性的日程创建成自动化。prompt 写清楚每次要检查什么、输出什么，例如“检查我的面试、投递和学习进度，给出今天最值得做的三件事”。
                """)
        public AutomationView createAutomation(
                @P("简短名称，例如“每日求职回顾”") String title,
                @P("每次运行时发给 Ardor 的完整任务说明，最多 20000 字") String prompt,
                @P("6 段 cron 表达式，例如 0 0 9 * * *") String cronExpression,
                @P(value = "IANA 时区，例如 Asia/Shanghai；不确定传空字符串", required = false) String timezone) {
            return service.createView(context.userId(), title, prompt, cronExpression, timezone);
        }

        @Tool(name = "update_automation", value = "修改当前用户的定时自动化任务；只传用户要求修改的字段，保留其余配置。修改执行时间时重新计算下一次运行")
        public AutomationView updateAutomation(
                @P("自动化任务 UUID") String automationId,
                @P(value = "新名称；不修改传 null", required = false) String title,
                @P(value = "新的每次运行任务说明；不修改传 null", required = false) String prompt,
                @P(value = "新的 6 段 cron 表达式；不修改传 null", required = false) String cronExpression,
                @P(value = "新的 IANA 时区；不修改传 null", required = false) String timezone,
                @P(value = "是否启用；不修改传 null", required = false) Boolean enabled) {
            return service.updateView(context.userId(), uuid(automationId, "自动化任务 ID"),
                    title, prompt, cronExpression, timezone, enabled);
        }

        @Tool(name = "delete_automation", value = "提议删除当前用户的定时自动化任务。只显示确认按钮，用户点击后才停止未来的运行；已产生的对话保留")
        public Map<String, Object> deleteAutomation(@P("自动化任务 UUID") String automationId) {
            UUID id = uuid(automationId, "自动化任务 ID");
            var automation = service.getView(context.userId(), id);
            return context.proposals().propose("automation", id, automation.title(),
                    "停止未来的所有定时运行，已产生的对话保留", "/api/automations/" + id);
        }


        private static UUID uuid(String value, String label) {
            try { return UUID.fromString(value); }
            catch (RuntimeException failure) { throw new IllegalArgumentException(label + " 格式不正确"); }
        }
    }
}
