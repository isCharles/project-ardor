package com.projectardor.assistant.memory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class StructuredSummaryTests {
    @Test
    void serializesToJsonWithNonEmptyFields() {
        var summary = new StructuredSummary(
                List.of("学习Spring Boot"),
                List.of("使用MySQL"),
                List.of(),
                List.of(new StructuredSummary.Entity("conversation", "abc-123", "测试对话")),
                List.of(),
                List.of(),
                "用户正在学习Spring Boot");

        String json = summary.toJson();

        assertThat(json).contains("goals");
        assertThat(json).contains("学习Spring Boot");
        assertThat(json).contains("decisions");
        assertThat(json).contains("entities");
        assertThat(json).doesNotContain("corrections");
        assertThat(json).doesNotContain("unresolved");
    }

    @Test
    void deserializesFromValidJson() {
        String json = """
                {
                  "goals": ["学习Java"],
                  "decisions": ["使用PostgreSQL"],
                  "narrative": "用户在学习Java"
                }
                """;

        var summary = StructuredSummary.fromJson(json);

        assertThat(summary).isNotNull();
        assertThat(summary.goals()).containsExactly("学习Java");
        assertThat(summary.decisions()).containsExactly("使用PostgreSQL");
        assertThat(summary.narrative()).isEqualTo("用户在学习Java");
    }

    @Test
    void returnsNullForInvalidJson() {
        assertThat(StructuredSummary.fromJson("not json")).isNull();
        assertThat(StructuredSummary.fromJson("{invalid}")).isNull();
    }

    @Test
    void convertsToPlainTextWithAllSections() {
        var summary = new StructuredSummary(
                List.of("目标1", "目标2"),
                List.of("决定1"),
                List.of("纠正1"),
                List.of(new StructuredSummary.Entity("id", "uuid-1", "描述")),
                List.of("问题1"),
                List.of(new StructuredSummary.ToolConclusion("search", "找到结果")),
                "整体概述");

        String plain = summary.toPlainText();

        assertThat(plain).contains("用户目标：目标1；目标2");
        assertThat(plain).contains("已做决定：决定1");
        assertThat(plain).contains("用户纠正：纠正1");
        assertThat(plain).contains("关键实体：id uuid-1(描述)");
        assertThat(plain).contains("未解决问题：问题1");
        assertThat(plain).contains("工具结论：search→找到结果");
        assertThat(plain).contains("概述：整体概述");
    }

    @Test
    void hasMinimumStructureRequiresGoalsDecisionsOrNarrative() {
        var empty = new StructuredSummary(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "");
        assertThat(empty.hasMinimumStructure()).isFalse();

        var withGoals = new StructuredSummary(List.of("目标"), List.of(), List.of(), List.of(), List.of(), List.of(), "");
        assertThat(withGoals.hasMinimumStructure()).isTrue();

        var withNarrative = new StructuredSummary(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "概述");
        assertThat(withNarrative.hasMinimumStructure()).isTrue();
    }

    @Test
    void estimatedInformationDensityCountsAllStructuredItems() {
        var summary = new StructuredSummary(
                List.of("g1", "g2"),
                List.of("d1"),
                List.of("c1", "c2", "c3"),
                List.of(new StructuredSummary.Entity("t", "id", "desc")),
                List.of("u1"),
                List.of(new StructuredSummary.ToolConclusion("tool", "conclusion")),
                "narrative");

        assertThat(summary.estimatedInformationDensity()).isEqualTo(9);
    }

    @Test
    void fromPlainTextCreatesNarrativeOnlySummary() {
        var summary = StructuredSummary.fromPlainText("这是一段普通的文本摘要");

        assertThat(summary.goals()).isEmpty();
        assertThat(summary.decisions()).isEmpty();
        assertThat(summary.narrative()).isEqualTo("这是一段普通的文本摘要");
        assertThat(summary.hasMinimumStructure()).isTrue();
    }

    @Test
    void toleratesUnknownFieldsInJson() {
        String json = """
                {
                  "goals": ["目标"],
                  "unknownField": "ignored",
                  "narrative": "概述"
                }
                """;

        var summary = StructuredSummary.fromJson(json);

        assertThat(summary).isNotNull();
        assertThat(summary.goals()).containsExactly("目标");
    }
}
