package com.projectardor.resume.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ResumeScoringRecommendationsTests {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void sortsByPriorityAndRejectsFabricatedEvidence() {
        var input = mapper.createArrayNode();
        input.addObject().put("priority", "P2").put("criterionId", "precision").put("evidence", "Built a queue")
                .put("problem", "Unclear scope").put("action", "State verified scope").put("example", "");
        input.addObject().put("priority", "P0").put("criterionId", "timeline").put("evidence", "")
                .put("problem", "No dates").put("action", "Add actual dates").put("example", "[verified start]–[verified end]");
        assertEquals("P0", ResumeScoringRecommendations.validate(input, "Built a queue").get(0).priority());
        ((tools.jackson.databind.node.ObjectNode) input.get(0)).put("evidence", "Invented revenue");
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRecommendations.validate(input, "Built a queue"));
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringRecommendations.validate(mapper.createArrayNode(), ""));
    }
}
