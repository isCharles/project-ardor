package com.projectardor.materials.resume.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ResumeScoringRubricTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SOURCE = "Designed a queue. Reduced observed latency from 80ms to 40ms in a load test.";

    private ArrayNode ratings(int level) {
        ArrayNode nodes = mapper.createArrayNode();
        ResumeScoringRubric.CRITERIA.forEach(c -> {
            var node = nodes.addObject().put("id", c.id())
                    .put("evidence", level == 0 ? "" : "Designed a queue.").put("rationale", "Synthetic fixture assessment");
            var checks = node.putArray("checks");
            for (int i = 0; i < 4; i++) checks.add(i < level);
        });
        return nodes;
    }

    @Test void usesFixedWeightsAndStableOrder() {
        assertEquals(100, ResumeScoringRubric.CRITERIA.stream().mapToInt(ResumeScoringRubric.Criterion::weight).sum());
        var input = ratings(3);
        var result = ResumeScoringRubric.evaluate(input, SOURCE);
        assertEquals(75, result.overallScore());
        assertTrue(result.categoryScores().values().stream().allMatch(score -> score == 75));
        assertEquals(result, ResumeScoringRubric.evaluate(input, SOURCE));
        ((ArrayNode) input.get(0).get("checks")).set(3, mapper.getNodeFactory().booleanNode(true));
        assertEquals(78, ResumeScoringRubric.evaluate(input, SOURCE).overallScore());
        assertEquals(85, ResumeScoringRubric.evaluate(input, SOURCE).categoryScores().get("content"));
    }

    @Test void acceptsNoEvidenceOnlyForMissingCriteria() {
        assertEquals(0, ResumeScoringRubric.evaluate(ratings(0), SOURCE).overallScore());
        var input = ratings(1);
        ((ObjectNode) input.get(0)).put("evidence", "");
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubric.evaluate(input, SOURCE));
    }

    @Test void rejectsMissingDuplicateUnknownAndUnboundedRatings() {
        var missing = ratings(3); missing.remove(0);
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubric.evaluate(missing, SOURCE));
        var duplicate = ratings(3); ((ObjectNode) duplicate.get(1)).put("id", "completeness");
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubric.evaluate(duplicate, SOURCE));
        var unknown = ratings(3); ((ObjectNode) unknown.get(0)).put("id", "prestige");
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubric.evaluate(unknown, SOURCE));
        var fractional = ratings(3); ((ArrayNode) fractional.get(0).get("checks")).set(0, mapper.getNodeFactory().numberNode(0.5));
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubric.evaluate(fractional, SOURCE));
        var over = ratings(3); ((ObjectNode) over.get(0)).putArray("checks").add(true);
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubric.evaluate(over, SOURCE));
    }

    @Test void modelSuppliedLevelsCannotOverrideTheChecklist() {
        var input = ratings(1);
        input.forEach(node -> ((ObjectNode) node).put("level", 4).put("overallScore", 100));
        assertEquals(25, ResumeScoringRubric.evaluate(input, SOURCE).overallScore());
        assertTrue(ResumeScoringRubric.CRITERIA.stream().allMatch(c -> c.conditions().size() == 4 && c.englishConditions().size() == 4));
        assertTrue(ResumeScoringRubric.prompt().contains("不得整体印象打等级"));
    }

    @Test void rejectsFabricatedQuotesAndNormalizesOnlyWhitespace() {
        var input = ratings(3); ((ObjectNode) input.get(0)).put("evidence", "Raised revenue by 200%.");
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubric.evaluate(input, SOURCE));
        ((ObjectNode) input.get(0)).put("evidence", "Designed\na queue.");
        assertEquals(75, ResumeScoringRubric.evaluate(input, SOURCE).overallScore());
    }
}
