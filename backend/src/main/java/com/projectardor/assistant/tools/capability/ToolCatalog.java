package com.projectardor.assistant.tools.capability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Explicit capability registry for every agent tool. Anything missing here is treated
 * as a write that always asks, so a forgotten tool fails closed rather than open.
 */
public final class ToolCatalog {

    public record Risk(ToolModule module, Capability capability) {}

    private static final Map<String, Risk> RISKS;

    static {
        Map<String, Risk> risks = new LinkedHashMap<>();
        register(risks, ToolModule.PROFILE, Capability.READ, "get_profile");
        register(risks, ToolModule.WEB, Capability.READ, "search_web");
        register(risks, ToolModule.SYSTEM, Capability.READ, "get_ai_job");

        register(risks, ToolModule.KNOWLEDGE, Capability.READ, "list_knowledge_documents", "search_knowledge");
        register(risks, ToolModule.KNOWLEDGE, Capability.CREATE, "research_knowledge_from_web");
        register(risks, ToolModule.KNOWLEDGE, Capability.DELETE, "delete_knowledge_document");

        register(risks, ToolModule.LEARNING, Capability.READ, "list_learning_plans", "get_learning_plan");
        register(risks, ToolModule.LEARNING, Capability.CREATE, "create_learning_plan");
        register(risks, ToolModule.LEARNING, Capability.UPDATE, "update_learning_plan", "begin_learning_plan");
        register(risks, ToolModule.LEARNING, Capability.DELETE, "delete_learning_plan");

        register(risks, ToolModule.MEMORY, Capability.READ, "list_memories");
        register(risks, ToolModule.MEMORY, Capability.CREATE, "add_memory");
        register(risks, ToolModule.MEMORY, Capability.UPDATE, "update_memory");
        register(risks, ToolModule.MEMORY, Capability.DELETE, "delete_memory", "clear_all_memories");

        register(risks, ToolModule.APPLICATIONS, Capability.READ, "get_application_rhythm");
        register(risks, ToolModule.APPLICATIONS, Capability.UPDATE,
                "record_application_count", "set_application_rhythm");

        register(risks, ToolModule.CALENDAR, Capability.READ, "list_calendar_tasks", "list_recurring_tasks");
        register(risks, ToolModule.CALENDAR, Capability.CREATE, "create_calendar_task", "create_recurring_task");
        register(risks, ToolModule.CALENDAR, Capability.UPDATE, "update_calendar_task", "update_recurring_task");
        register(risks, ToolModule.CALENDAR, Capability.DELETE, "delete_calendar_task", "delete_recurring_task");

        register(risks, ToolModule.RESUMES, Capability.READ,
                "list_resumes", "get_resume_analysis_status", "get_resume_analysis");
        register(risks, ToolModule.RESUMES, Capability.CREATE, "analyze_resume");
        register(risks, ToolModule.RESUMES, Capability.DELETE, "delete_resume");

        register(risks, ToolModule.INTERVIEWS, Capability.READ,
                "list_interviews", "get_next_question", "get_interview_evaluation");
        register(risks, ToolModule.INTERVIEWS, Capability.CREATE, "create_interview");
        register(risks, ToolModule.INTERVIEWS, Capability.UPDATE, "submit_interview_answer");
        register(risks, ToolModule.INTERVIEWS, Capability.EXECUTE, "finish_interview", "cancel_interview");
        register(risks, ToolModule.INTERVIEWS, Capability.DELETE, "delete_interview");

        register(risks, ToolModule.RECAPS, Capability.READ,
                "list_interview_recap_jobs", "list_interview_recaps", "get_interview_recap");
        register(risks, ToolModule.RECAPS, Capability.CREATE, "organize_interview_recap");
        register(risks, ToolModule.RECAPS, Capability.DELETE, "delete_interview_recap");

        register(risks, ToolModule.CARDS, Capability.READ, "list_due_memory_cards", "list_memory_cards");
        register(risks, ToolModule.CARDS, Capability.CREATE, "create_memory_card");
        register(risks, ToolModule.CARDS, Capability.UPDATE,
                "review_memory_card", "update_memory_card", "suspend_memory_card");
        register(risks, ToolModule.CARDS, Capability.DELETE, "delete_memory_card", "delete_all_memory_cards");

        register(risks, ToolModule.OPPORTUNITIES, Capability.READ,
                "list_job_opportunities", "get_job_opportunity");
        register(risks, ToolModule.OPPORTUNITIES, Capability.CREATE,
                "create_job_opportunity", "add_job_opportunity_round");
        register(risks, ToolModule.OPPORTUNITIES, Capability.UPDATE,
                "update_job_opportunity", "update_job_opportunity_round");
        register(risks, ToolModule.OPPORTUNITIES, Capability.DELETE,
                "delete_job_opportunity", "delete_job_opportunity_round");
        RISKS = Collections.unmodifiableMap(risks);
    }

    private static void register(Map<String, Risk> risks, ToolModule module, Capability capability, String... tools) {
        for (String tool : tools) {
            if (risks.put(tool, new Risk(module, capability)) != null) {
                throw new IllegalStateException("工具重复登记：" + tool);
            }
        }
    }

    /** Returns the registered risk, or null for an unregistered tool (which must ask). */
    public static Risk describe(String tool) {
        return tool == null ? null : RISKS.get(tool);
    }

    public static Set<ToolModule> modules() {
        EnumSet<ToolModule> modules = EnumSet.noneOf(ToolModule.class);
        for (Risk risk : RISKS.values()) modules.add(risk.module());
        return modules;
    }

    public static Set<String> tools() {
        return RISKS.keySet();
    }

    private ToolCatalog() {
    }
}
