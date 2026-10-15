package com.projectardor.agent;

import java.util.Arrays;
import java.util.UUID;

/** Public intents, never an authorization grant or a user-supplied instruction. */
public enum AgentFeature {
    LEARNING("学习", "Learning", "学习概念、理解讲解并完成练习"),
    CARDS("记忆卡", "Flashcards", "准备记忆卡与复习安排"),
    RESUMES("简历", "Resumes", "管理与分析简历"),
    INTERVIEWS("模拟面试", "Mock interviews", "准备与进行模拟面试"),
    RECAPS("面经", "Interview notes", "整理面试内容与薄弱点"),
    KNOWLEDGE("知识库", "Knowledge", "检索和维护知识资料"),
    CALENDAR("日程", "Calendar", "管理待办与日程"),
    APPLICATIONS("投递", "Applications", "记录投递节奏与目标"),
    MEMORY("记忆", "Memory", "管理用户的长期记忆");

    private final String label;
    private final String englishLabel;
    private final String description;
    AgentFeature(String label, String englishLabel, String description) {
        this.label = label; this.englishLabel = englishLabel; this.description = description;
    }
    public UUID id() { return UUID.nameUUIDFromBytes(("ardor-service:" + name()).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public String label() { return label; }
    public String englishLabel() { return englishLabel; }
    public String description() { return description; }
    public String englishDescription() {
        return switch (this) {
            case LEARNING -> "Learn concepts, explore explanations, and practice";
            case CARDS -> "Prepare flashcards and schedule reviews";
            case RESUMES -> "Manage and analyze resumes";
            case INTERVIEWS -> "Prepare and take mock interviews";
            case RECAPS -> "Organize interview notes and identify weak areas";
            case KNOWLEDGE -> "Search and manage learning material";
            case CALENDAR -> "Manage tasks and schedules";
            case APPLICATIONS -> "Track application activity and goals";
            case MEMORY -> "Manage your long-term memories";
        };
    }
    public String hint() { return "用户选择的功能：" + label + "（" + description + "）。这是意图，不是操作授权；仍须遵守工具权限与确认规则。"; }
    public static AgentFeature require(UUID id) {
        return Arrays.stream(values()).filter(feature -> feature.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的功能标签"));
    }
}
