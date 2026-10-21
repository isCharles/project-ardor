package com.projectardor.materials.resume.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ResumeScoringRubricV3Tests {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SOURCE = "Designed a queue. Reduced observed latency from 80ms to 40ms in a load test.";

    private ArrayNode ratings(int... levels) {
        if (levels.length != ResumeScoringRubricV3.DIMENSIONS.size()) throw new IllegalArgumentException("wrong fixture size");
        ArrayNode nodes = mapper.createArrayNode();
        for (int index = 0; index < ResumeScoringRubricV3.DIMENSIONS.size(); index++) {
            var dimension = ResumeScoringRubricV3.DIMENSIONS.get(index);
            int level = levels[index];
            nodes.addObject().put("id", dimension.id()).put("level", level)
                    .put("evidence", level == 0 ? "" : "Designed a queue.")
                    .put("rationale", "Synthetic fixture assessment");
        }
        return nodes;
    }

    @Test void weightsAreFixedAndAnOrdinaryProfileStaysLow() {
        assertEquals(100, ResumeScoringRubricV3.DIMENSIONS.stream().mapToInt(ResumeScoringRubricV3.Dimension::weight).sum());
        var result = ResumeScoringRubricV3.evaluate(ratings(2, 1, 2, 2, 2, 1), SOURCE);
        assertTrue(result.overallScore() >= 40 && result.overallScore() <= 45, "An ordinary draft should land around 40");
        assertEquals(50, result.dimensionScores().get("technicalDepth"));
        assertEquals("C", result.dimensionGrades().get("technicalDepth"));
        assertEquals("E", result.dimensionGrades().get("outcomes"));
        assertEquals("E", result.dimensionGrades().get("background"));
    }

    @Test void topEvidenceIsRareAndProducesSOrA() {
        var top = ResumeScoringRubricV3.evaluate(ratings(4, 4, 4, 4, 4, 4), SOURCE);
        assertEquals(100, top.overallScore());
        assertTrue(top.dimensionGrades().values().stream().allMatch("S"::equals));
        assertEquals("A", ResumeScoringRubricV3.grade(80));
        assertEquals("B", ResumeScoringRubricV3.grade(65));
        assertEquals("C", ResumeScoringRubricV3.grade(50));
        assertEquals("D", ResumeScoringRubricV3.grade(35));
        assertEquals("E", ResumeScoringRubricV3.grade(20));
        assertEquals("F", ResumeScoringRubricV3.grade(19));
    }

    @Test void rejectsMissingDuplicateOutOfRangeAndFabricatedEvidence() {
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubricV3.evaluate(ratings(4, 4, 4, 4, 4), SOURCE));
        ArrayNode duplicate = ratings(2, 2, 2, 2, 2, 2);
        duplicate.add(duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubricV3.evaluate(duplicate, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubricV3.evaluate(ratings(5, 2, 2, 2, 2, 2), SOURCE));
        ArrayNode fabricated = ratings(2, 2, 2, 2, 2, 2);
        ((ObjectNode) fabricated.get(0)).put("evidence", "A fact that is not in the resume");
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubricV3.evaluate(fabricated, SOURCE));
        ArrayNode missingRationale = ratings(2, 2, 2, 2, 2, 2);
        ((ObjectNode) missingRationale.get(0)).put("rationale", "");
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRubricV3.evaluate(missingRationale, SOURCE));
    }

    @Test void zeroLevelDoesNotRequireEvidence() {
        ArrayNode zero = ratings(0, 0, 0, 0, 0, 0);
        var result = ResumeScoringRubricV3.evaluate(zero, SOURCE);
        assertEquals(0, result.overallScore());
        assertTrue(result.dimensionGrades().values().stream().allMatch("F"::equals));
    }

    @Test void levelZeroMayOmitEvidenceFieldEntirely() {
        ArrayNode nodes = mapper.createArrayNode();
        ResumeScoringRubricV3.DIMENSIONS.forEach(dimension ->
                nodes.addObject().put("id", dimension.id()).put("level", 0));
        var result = ResumeScoringRubricV3.evaluate(nodes, SOURCE);
        assertEquals(0, result.overallScore());
    }

    @Test void toleratesHarmlessFormattingAroundQuotedEvidence() {
        ArrayNode nodes = ratings(2, 2, 2, 2, 2, 2);
        ((ObjectNode) nodes.get(0)).put("evidence", "“Designed a queue…”");
        assertEquals(50, ResumeScoringRubricV3.evaluate(nodes, SOURCE).dimensionScores().get("technicalDepth"));
    }

    @Test void familyProfilesAreFixedAndWeightsStayComparable() {
        assertEquals(7, ResumeScoringFamilies.FAMILIES.size());
        assertEquals("general", ResumeScoringFamilies.resolve(null).id());
        assertEquals("general", ResumeScoringFamilies.resolve("not-a-family").id());
        assertEquals("algorithm", ResumeScoringFamilies.resolve("algorithm").id());
        assertTrue(ResumeScoringFamilies.prompt(ResumeScoringFamilies.byId("algorithm")).contains("算法"));
        assertTrue(ResumeScoringFamilies.prompt(ResumeScoringFamilies.byId("sales")).contains("配额/营收"));
    }
}
