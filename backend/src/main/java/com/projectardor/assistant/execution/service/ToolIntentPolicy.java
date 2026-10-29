package com.projectardor.assistant.execution.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative capability scope from the actual user's request, never tool results or service tags. */
public final class ToolIntentPolicy {
    private ToolIntentPolicy() {}
    private static final Set<String> READS = Set.of("get_profile", "search_web", "list_knowledge_documents", "search_knowledge",
            "list_learning_plans", "get_learning_plan", "list_memories", "get_application_rhythm", "list_calendar_tasks",
            "list_recurring_tasks", "list_resumes", "get_resume_analysis_status", "get_resume_analysis", "list_interviews",
            "get_next_question", "get_ai_job", "get_interview_evaluation", "list_interview_recap_jobs", "list_interview_recaps",
            "get_interview_recap", "list_due_memory_cards", "list_memory_cards", "list_job_opportunities",
            "get_job_opportunity", "list_automations", "get_automation", "get_calendar_advice", "propose_plan");
    public static boolean isRead(String tool) { return READS.contains(tool); }
    /** A narrowed/revoked instruction cannot inherit an unrestricted old capability.
     * Target-specific exemptions require a fresh positive request, not model interpretation. */
    public static boolean permitsInherited(String tool, String currentRequest, String interruptedRequest) {
        if (currentRequest != null && Pattern.compile("不要|不用|不需要|别|取消|停止|撤销|算了|保留|除了|除外|仅|只|先不|don['’]?t|do not|never|cancel|stop|revoke|except|only|keep|instead", Pattern.CASE_INSENSITIVE)
                .matcher(currentRequest).find()) return false;
        return permits(tool, interruptedRequest);
    }
    public static boolean permits(String tool, String trustedRequest) {
        String request = trustedRequest == null ? "" : trustedRequest.toLowerCase(Locale.ROOT)
                .replaceAll("(?s)```.*?```|<untrusted_external_content[^>]*>.*?</untrusted_external_content>", "")
                .replaceAll("(?m)^\\s*>.*$", "");
        if (DATE_WRITES.contains(tool) && revokesDateCorrection(request)) return false;
        String domain = switch (tool) {
            case "add_memory", "update_memory" -> "记住|记忆|记一下|remember|memor(?:y|ies)";
            case "create_calendar_task", "update_calendar_task", "create_recurring_task", "update_recurring_task" -> "日历|日程|待办|安排|提醒|calendar|schedule|remind|task";
            case "update_learning_plan", "begin_learning_plan" -> "学习|讲解|练习|learn|lesson|study|practice";
            case "create_memory_card", "update_memory_card", "review_memory_card", "suspend_memory_card" -> "记忆卡|卡片|复习|flashcard|card|review";
            case "create_interview", "submit_interview_answer", "finish_interview", "cancel_interview" -> "面试|interview";
            case "organize_interview_recap" -> "面经|面试|纪要|复盘|interview|minutes|recap";
            case "create_job_opportunity", "update_job_opportunity", "add_job_opportunity_round",
                    "update_job_opportunity_round" ->
                    "面试|邀约|邀请|流程|进度|岗位|interview|opportunit|offer";
            case "research_knowledge_from_web" -> "知识库|收录|资料|knowledge|material";
            case "analyze_resume" -> "简历|resume|résumé";
            case "update_latex_project", "replace_latex_main_file", "freeze_latex_version" ->
                    "简历|LaTeX|latex|排版|模板|resume";
            case "rename_latex_project" -> "简历|resume|résumé|标题|名字|名称|title|name";
            case "record_application_count", "set_application_rhythm", "update_application_advice" -> "投递|投了|已投|申请|application|applied";
            case "create_automation", "update_automation", "delete_automation" ->
                    "自动化|定时|定期|每天|每周|每月|每年|以后|今后|automation|cron|schedule|daily|weekly|monthly";
            case "update_calendar_advice", "delete_calendar_advice" ->
                    "日历|日程|建议|规划|安排|接下来|未来|calendar|advice|plan|schedule|next";
            default -> "(?!)";
        };
        if (DATE_WRITES.contains(tool) && permitsDateCorrection(request)) {
            return java.util.Arrays.stream(request.split("[。！？!?;；\\n，,]"))
                    .anyMatch(clause -> permitsDateCorrection(clause) && tool.equals(dateContext(clause)));
        }
        String action = switch (tool) {
            case "add_memory" -> "记住|记一下|记录|保存|remember|record|save";
            case "update_calendar_task", "update_recurring_task", "update_learning_plan" -> "修改|修正|调整|改成|改到|改为|更新|update|change|edit|correct|reschedule";
            case "update_memory", "update_memory_card" -> "修改|修正|调整|改成|更新|update|change|edit|correct";
            case "create_calendar_task", "create_recurring_task" -> "创建|新增|添加|安排|提醒|create|add|schedule|remind";
            case "begin_learning_plan" -> "开始|学习|讲解|做|start|begin|learn|study|practice";
            case "create_memory_card" -> "创建|新增|添加|生成|整理|制作|create|add|generate|make|prepare";
            case "review_memory_card" -> "复习|回答|评分|review|answer|rate";
            case "suspend_memory_card" -> "暂停|恢复|suspend|resume";
            case "create_interview" -> "创建|新增|开始|做|准备|create|start|begin|prepare";
            case "submit_interview_answer" -> "提交|回答|submit|answer";
            case "finish_interview" -> "结束|完成|finish|complete";
            case "cancel_interview" -> "取消|cancel";
            case "organize_interview_recap" -> "整理|生成|复盘|organize|generate|recap";
            case "create_job_opportunity" ->
                    "创建|新增|添加|记录|记一下|记下|录入|保存|收到|拿到|接到|create|add|record|log|save";
            case "update_job_opportunity", "update_job_opportunity_round" ->
                    "修改|更新|改成|改到|变更|标记|设为|设置为|推进|update|change|mark|set|move";
            case "add_job_opportunity_round" ->
                    "添加|新增|加|安排|创建|补录|add|create|schedule";
            case "research_knowledge_from_web" -> "收录|添加|保存|整理|收集|save|add|collect|research";
            case "analyze_resume" -> "分析|评估|analy[sz]e|evaluate|review";
            case "update_latex_project", "replace_latex_main_file" ->
                    "修改|调整|更新|改成|改为|重写|润色|排版|update|change|edit|rewrite|reword";
            case "freeze_latex_version" -> "定稿|冻结|正式版本|正式版|可以投递|freeze|finalize";
            case "rename_latex_project" -> "改名|改个名|重命名|改标题|换个名字|换名字|名字改成|名称改成|改成|改为|rename|title";
            case "record_application_count" -> "记录|record|log";
            case "set_application_rhythm" -> "设置|调整|修改|设为|set|change|update";
            case "update_application_advice" -> "保存|更新|写入|记录|建议|判断|节奏|save|update|write|record|advice|pace";
            case "create_automation" -> "创建|新增|添加|设置|安排|建立|每天|每周|每月|定期|定时|以后|今后|create|add|set|schedule|every";
            case "update_automation" -> "修改|调整|更新|改成|改为|暂停|恢复|启用|停用|关闭|打开|update|change|pause|resume|enable|disable";
            case "delete_automation" -> "删除|删掉|取消|停止|移除|delete|remove|cancel|stop";
            case "update_calendar_advice" -> "保存|更新|修改|写入|记录|规划|建议|安排|save|update|change|write|record|plan|advice";
            case "delete_calendar_advice" -> "删除|清空|清除|移除|delete|clear|remove";
            default -> "(?!)";
        };
        String imperative = "(?:^|[。！？;；\\n，,])\\s*(?:(?:请你?|麻烦|帮我|我想要?|我要|我希望你?|给我|please|can you|could you|i want you to|help me)\\s*)*(?:(?:把|将)[^。！？\\n：:]{1,100})?(?:" + action + ")";
        var domainPattern = Pattern.compile(domain, Pattern.CASE_INSENSITIVE);
        var actionPattern = Pattern.compile(imperative, Pattern.CASE_INSENSITIVE);
        return java.util.Arrays.stream(request.split("[。！？!?;；\\n，,]"))
                .anyMatch(clause -> domainPattern.matcher(clause).find() && (actionPattern.matcher(clause).find()
                    || tool.equals("record_application_count") && clause.matches("(?s).*(?:今天|昨天|today|yesterday).*(?:投了|已投|applied).*[0-9]+.*")));
    }
    private static boolean permitsDateCorrection(String request) {
        String prefix = "^\\s*(?:(?:请你?|麻烦|帮我|我希望你?|please|can you|could you|help me)\\s*)*";
        String update = "(?:把|将)[^。！？\\n：:]{1,100}(?:修改|调整|改成|改到|改为|挪到|移到|排到|推迟|提前)|(?:修改|调整|改一下|改下|改成|改到|改为|挪到|移到|排到|推迟|提前|reschedule|change|move|shift|update|postpone)";
        return java.util.Arrays.stream(request.split("[。！？!?;；\\n，,]"))
                .anyMatch(clause -> Pattern.compile(prefix + "(?:" + update + ")", Pattern.CASE_INSENSITIVE).matcher(clause).find()
                    && Pattern.compile("日期|时间|明天|后天|今天|[上下本这]周|周[一二三四五六日天]|星期[一二三四五六日天]|[0-9]+[号日点时]|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}:[0-9]{2}|date|time|tomorrow|next week|monday|tuesday|wednesday|thursday|friday|saturday|sunday|january|february|march|april|may|june|july|august|september|october|november|december", Pattern.CASE_INSENSITIVE).matcher(clause).find());
    }
    private static final Set<String> DATE_WRITES = Set.of("update_calendar_task", "update_recurring_task", "update_learning_plan");
    public static boolean revokesDateCorrection(String request) {
        return request != null && Pattern.compile("算了|撤销|先不改|(?:别|不要|不用|不需要|停止)(?:再)?(?:修改|调整|改|挪|移|排|推迟|提前)|never ?mind|\\b(?:don['’]?t|do not|stop)\\s+(?:change|move|reschedule|edit|update|postpone)|\\bcancel\\b", Pattern.CASE_INSENSITIVE).matcher(request).find();
    }
    /** Exactly one module, from authenticated UI context or the user's wording, not assistant prose. */
    public static String dateContext(String wording) {
        if (wording == null) return "";
        String clean = wording.toLowerCase(Locale.ROOT).replaceAll("(?s)```.*?```|<untrusted_external_content[^>]*>.*?</untrusted_external_content>", "").replaceAll("(?m)^\\s*>.*$", "");
        if (Pattern.compile("简历|合同|记忆|面试|面经|知识库|投递|\\b(?:resume|résumé|contract|memory|interview|knowledge|application)\\b").matcher(clean).find()) return "";
        boolean learning = Pattern.compile("学习|讲解|练习|\\b(?:learning|lesson|study|practice)\\b").matcher(clean).find();
        boolean calendar = Pattern.compile("日历|日程|待办|提醒|\\b(?:calendar|tasks?|reminder)\\b").matcher(clean).find();
        if (learning == calendar) return "";
        if (learning) return "update_learning_plan";
        return Pattern.compile("重复|每周|每天|定期|\\b(?:recurring|weekly|daily)\\b").matcher(clean).find() ? "update_recurring_task" : "update_calendar_task";
    }
    public static boolean permitsContextualDateUpdate(String tool, String request, String trustedContext) {
        String wording = request == null ? "" : request.toLowerCase(Locale.ROOT)
                .replaceAll("(?s)```.*?```|<untrusted_external_content[^>]*>.*?</untrusted_external_content>", "").replaceAll("(?m)^\\s*>.*$", "");
        if (!DATE_WRITES.contains(tool) || !tool.equals(trustedContext) || revokesDateCorrection(wording) || !permitsDateCorrection(wording)) return false;
        // Do not reinterpret an arbitrary named object (e.g. a contract) as an
        // old plan. Only a generic/pronominal date correction can inherit scope.
        return java.util.Arrays.stream(wording.split("[。！？!?;；\\n，,]")).anyMatch(clause -> {
            if (!permitsDateCorrection(clause)) return false;
            String residual = clause.toLowerCase(Locale.ROOT)
                    .replaceAll("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:t[0-9:.]+z)?", "")
                    .replaceAll("\\b(?:please|can|could|you|help|me|reschedule|change|move|shift|update|postpone|this|that|it|the|first|second|third|one|to|for|on|at|date|time|tomorrow|next|week|monday|tuesday|wednesday|thursday|friday|saturday|sunday|january|february|march|april|may|june|july|august|september|october|november|december)\\b", "")
                    .replaceAll("请你?|麻烦|帮我|我希望你?|把|将|这个|那个|这些|那些|它|一下|修改|调整|改一下|改下|改成|改到|改为|挪到|移到|排到|推迟|提前|日期|时间|明天|后天|今天|星期|[上下本这]?周|上午|下午|晚上|中午|的|第|[一二三四五六七八九十日天个号点时分秒]", "")
                    .replaceAll("[\\s\\p{P}0-9]+", "");
            return residual.isEmpty();
        });
    }

    /**
     * Text the agent must copy from the user's own message verbatim, such as a mock-interview
     * answer. Memory no longer uses this rule; it applies only where the submitted text is
     * presented as the user's own words.
     */
    public static boolean verbatimQuote(String trustedRequest, String quoted) {
        if (quoted == null || quoted.isBlank()) return false;
        String wording = normalize(quoted);
        return !wording.isBlank() && normalize(trustedRequest).contains(wording);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]+", "");
    }
}
