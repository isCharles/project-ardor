package com.projectardor.agent.service;

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
            "get_interview_recap", "list_due_memory_cards", "list_memory_cards");
    public static boolean isRead(String tool) { return READS.contains(tool); }
    public static boolean permits(String tool, String trustedRequest) {
        String request = trustedRequest == null ? "" : trustedRequest.toLowerCase(Locale.ROOT)
                .replaceAll("(?s)```.*?```|<untrusted_external_content[^>]*>.*?</untrusted_external_content>", "")
                .replaceAll("(?m)^\\s*>.*$", "");
        String domain = switch (tool) {
            case "add_memory", "update_memory" -> "记住|记忆|记一下|remember|memor(?:y|ies)";
            case "create_calendar_task", "update_calendar_task", "create_recurring_task", "update_recurring_task" -> "日历|日程|待办|安排|提醒|calendar|schedule|remind|task";
            case "update_learning_plan", "begin_learning_plan" -> "学习|讲解|练习|learn|lesson|study|practice";
            case "create_memory_card", "update_memory_card", "review_memory_card", "suspend_memory_card" -> "记忆卡|卡片|复习|flashcard|card|review";
            case "create_interview", "submit_interview_answer", "finish_interview", "cancel_interview" -> "面试|interview";
            case "organize_interview_recap", "update_interview_recap_metadata" -> "面经|面试|复盘|interview|recap";
            case "research_knowledge_from_web" -> "知识库|收录|资料|knowledge|material";
            case "analyze_resume" -> "简历|resume|résumé";
            case "record_application_count", "set_application_rhythm" -> "投递|投了|已投|申请|application|applied";
            default -> "(?!)";
        };
        String action = switch (tool) {
            case "add_memory" -> "记住|记一下|记录|保存|remember|record|save";
            case "update_memory", "update_calendar_task", "update_recurring_task", "update_learning_plan", "update_memory_card", "update_interview_recap_metadata" -> "修改|修正|调整|改成|更新|update|change|edit|correct|reschedule";
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
            case "research_knowledge_from_web" -> "收录|添加|保存|整理|收集|save|add|collect|research";
            case "analyze_resume" -> "分析|评估|analy[sz]e|evaluate|review";
            case "record_application_count" -> "记录|record|log";
            case "set_application_rhythm" -> "设置|调整|修改|设为|set|change|update";
            default -> "(?!)";
        };
        String imperative = "(?:^|[。！？;；\\n，,])\\s*(?:(?:请你?|麻烦|帮我|我想要?|我要|我希望你?|给我|please|can you|could you|i want you to|help me)\\s*)*(?:(?:把|将)[^。！？\\n：:]{1,100})?(?:" + action + ")";
        var domainPattern = Pattern.compile(domain, Pattern.CASE_INSENSITIVE);
        var actionPattern = Pattern.compile(imperative, Pattern.CASE_INSENSITIVE);
        return java.util.Arrays.stream(request.split("[。！？!?;；\\n，,]"))
                .anyMatch(clause -> domainPattern.matcher(clause).find() && (actionPattern.matcher(clause).find()
                    || tool.equals("record_application_count") && clause.matches("(?s).*(?:今天|昨天|today|yesterday).*(?:投了|已投|applied).*[0-9]+.*")));
    }
    /** Personal memory may quote only the user's own supplied wording, not retrieved assertions. */
    public static boolean groundedMemory(String trustedRequest, String memory) {
        if (memory == null || memory.isBlank()) return false;
        String wording = normalize(memory);
        return !wording.isBlank() && normalize(trustedRequest).contains(wording);
    }
    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]+", "");
    }
}
