package com.projectardor.materials.resume.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ResumeScoringContextTests {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void oldQueuedRulesCannotBeSilentlyScoredWithNewRules() {
        assertThrows(IllegalStateException.class, () -> ResumeScoringContext.requireSupported(java.util.Map.of()));
        var current = ResumeScoringContext.freeze("Java", "Requires Redis");
        assertDoesNotThrow(() -> ResumeScoringContext.requireSupported(current));
        var previous = new LinkedHashMap<>(current);
        previous.put("rubricVersion", "ardor-resume-rubric-v1");
        assertThrows(IllegalStateException.class, () -> ResumeScoringContext.requireSupported(previous));
        previous.put("rubricVersion", ResumeScoringRubric.VERSION);
        previous.put("promptVersion", "resume-analysis-v3");
        assertThrows(IllegalStateException.class, () -> ResumeScoringContext.requireSupported(previous));
    }
    @Test void sameTextAndConditionsHaveStableIdentityIndependentOfMapOrder() {
        var context = ResumeScoringContext.freeze("Java backend", "Uses Redis");
        var reordered = new LinkedHashMap<String,Object>();
        context.keySet().stream().sorted(java.util.Comparator.reverseOrder()).forEach(key -> reordered.put(key, context.get(key)));
        assertEquals(ResumeScoringContext.key("Built\r\na queue", context, mapper), ResumeScoringContext.key("Built\na queue", reordered, mapper));
        assertNotEquals(ResumeScoringContext.key("Built\na queue", context, mapper), ResumeScoringContext.key("Built a queue", context, mapper), "Paragraph structure is part of the evaluated input");
        assertNotEquals(ResumeScoringContext.key("Built a queue", context, mapper), ResumeScoringContext.key("Built a queue", ResumeScoringContext.freeze("Frontend", "Uses Redis"), mapper));
        assertNotEquals(ResumeScoringContext.key("Built a queue", context, mapper), ResumeScoringContext.key("Built a queue", ResumeScoringContext.freeze("Java backend", "Uses MySQL"), mapper));
        assertEquals("GENERAL", ResumeScoringContext.freeze("Java", "").get("mode"));
    }
    @Test void includesFullTextEvenWhenEvaluationIsTruncated() {
        String prefix = "a".repeat(ResumeScoringContext.TEXT_LIMIT);
        var context = ResumeScoringContext.freeze("", "");
        assertEquals(prefix, ResumeScoringContext.evaluatedText(prefix + "b"));
        assertNotEquals(ResumeScoringContext.key(prefix + "b", context, mapper), ResumeScoringContext.key(prefix + "c", context, mapper));
        var upgraded = new LinkedHashMap<>(context); upgraded.put("rubricVersion", "future-version");
        assertNotEquals(ResumeScoringContext.key(prefix, context, mapper), ResumeScoringContext.key(prefix, upgraded, mapper));
    }
    @Test void rejectsOversizedConditionsInsteadOfSilentlyTruncating() {
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringContext.freeze("a".repeat(241), ""));
        assertThrows(IllegalArgumentException.class, () -> ResumeScoringContext.freeze("", "a".repeat(20001)));
    }
}
