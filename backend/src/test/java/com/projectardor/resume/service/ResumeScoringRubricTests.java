package com.projectardor.resume.service;

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
        ResumeScoringRubric.CRITERIA.forEach(c -> nodes.addObject().put("id", c.id()).put("level", level)
                .put("evidence", level == 0 ? "" : "Designed a queue.").put("rationale", "Synthetic fixture assessment"));
        return nodes;
    }

    @Test void usesFixedWeightsAndStableOrder() {
        assertEquals(100, ResumeScoringRubric.CRITERIA.stream().mapToInt(ResumeScoringRubric.Criterion::weight).sum());
        var input = ratings(3);
        var result = ResumeScoringRubric.evaluate(input, SOURCE);
        assertEquals(75, result.overallScore());
        assertTrue(result.categoryScores().values().stream().allMatch(score -> score == 75));
        assertEquals(result, ResumeScoringRubric.evaluate(input, SOURCE));
        ((ObjectNode) input.get(0)).put("level", 4);
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
        var fractional = ratings(3); ((ObjectNode) fractional.get(0)).put("level", 2.5);
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubric.evaluate(fractional, SOURCE));
        var over = ratings(3); ((ObjectNode) over.get(0)).put("level", 5);
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubric.evaluate(over, SOURCE));
    }

    @Test void rejectsFabricatedQuotesAndNormalizesOnlyWhitespace() {
        var input = ratings(3); ((ObjectNode) input.get(0)).put("evidence", "Raised revenue by 200%.");
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubric.evaluate(input, SOURCE));
        ((ObjectNode) input.get(0)).put("evidence", "Designed\na queue.");
        assertEquals(75, ResumeScoringRubric.evaluate(input, SOURCE).overallScore());
    }
}
