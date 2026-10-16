package com.projectardor.resume.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import tools.jackson.databind.JsonNode;

public final class ResumeScoringRecommendations {
    private ResumeScoringRecommendations() {}
    public record Recommendation(String priority, String criterionId, String evidence, String problem, String action, String example) {}

    public static List<Recommendation> validate(JsonNode input, String text) {
        if (input == null || !input.isArray() || input.isEmpty() || input.size() > 20) throw invalid();
        List<Recommendation> result = new ArrayList<>();
        for (JsonNode node : input) {
            String priority = value(node, "priority", false);
            String id = value(node, "criterionId", false);
            String evidence = value(node, "evidence", true);
            if (!List.of("P0", "P1", "P2").contains(priority)
                    || ResumeScoringRubric.CRITERIA.stream().noneMatch(c -> c.id().equals(id))
                    || !text.replaceAll("\\s+", " ").contains(evidence.replaceAll("\\s+", " "))) throw invalid();
            result.add(new Recommendation(priority, id, evidence, value(node, "problem", false), value(node, "action", false), value(node, "example", true)));
        }
        result.sort(Comparator.comparing(Recommendation::priority).thenComparingInt(r ->
                java.util.stream.IntStream.range(0, ResumeScoringRubric.CRITERIA.size())
                        .filter(i -> ResumeScoringRubric.CRITERIA.get(i).id().equals(r.criterionId())).findFirst().orElseThrow()));
        return List.copyOf(result);
    }

    private static String value(JsonNode node, String field, boolean allowEmpty) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > 3000 || (!allowEmpty && value.textValue().isBlank())) throw invalid();
        return value.textValue().strip();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("简历优化建议不符合优先级或证据规范"); }
}
