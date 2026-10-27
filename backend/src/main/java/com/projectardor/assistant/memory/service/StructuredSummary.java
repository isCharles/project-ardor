package com.projectardor.assistant.memory.service;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Structured representation of a conversation summary.
 *
 * <p>Instead of free-text only, summaries are organized into semantic sections
 * that preserve the most important context for continued conversation:
 * user goals, decisions, corrections, entities, and unresolved items.</p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record StructuredSummary(
        List<String> goals,
        List<String> decisions,
        List<String> corrections,
        List<Entity> entities,
        List<String> unresolved,
        List<ToolConclusion> toolConclusions,
        String narrative) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record Entity(String type, String id, String description) {}
    public record ToolConclusion(String tool, String conclusion) {}

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return toPlainText();
        }
    }

    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        if (goals != null && !goals.isEmpty()) {
            sb.append("用户目标：").append(String.join("；", goals)).append("\n");
        }
        if (decisions != null && !decisions.isEmpty()) {
            sb.append("已做决定：").append(String.join("；", decisions)).append("\n");
        }
        if (corrections != null && !corrections.isEmpty()) {
            sb.append("用户纠正：").append(String.join("；", corrections)).append("\n");
        }
        if (entities != null && !entities.isEmpty()) {
            sb.append("关键实体：");
            for (int i = 0; i < entities.size(); i++) {
                if (i > 0) sb.append("；");
                var e = entities.get(i);
                sb.append(e.type()).append(" ").append(e.id());
                if (e.description() != null) sb.append("(").append(e.description()).append(")");
            }
            sb.append("\n");
        }
        if (unresolved != null && !unresolved.isEmpty()) {
            sb.append("未解决问题：").append(String.join("；", unresolved)).append("\n");
        }
        if (toolConclusions != null && !toolConclusions.isEmpty()) {
            sb.append("工具结论：");
            for (int i = 0; i < toolConclusions.size(); i++) {
                if (i > 0) sb.append("；");
                var t = toolConclusions.get(i);
                sb.append(t.tool()).append("→").append(t.conclusion());
            }
            sb.append("\n");
        }
        if (narrative != null && !narrative.isBlank()) {
            sb.append("概述：").append(narrative);
        }
        return sb.toString().trim();
    }

    public static StructuredSummary fromJson(String json) {
        try {
            return MAPPER.readValue(json, StructuredSummary.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static StructuredSummary fromPlainText(String text) {
        return new StructuredSummary(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), text);
    }

    public boolean hasMinimumStructure() {
        return (goals != null && !goals.isEmpty())
                || (decisions != null && !decisions.isEmpty())
                || (narrative != null && !narrative.isBlank());
    }

    public int estimatedInformationDensity() {
        int items = 0;
        if (goals != null) items += goals.size();
        if (decisions != null) items += decisions.size();
        if (corrections != null) items += corrections.size();
        if (entities != null) items += entities.size();
        if (unresolved != null) items += unresolved.size();
        if (toolConclusions != null) items += toolConclusions.size();
        return items;
    }
}
