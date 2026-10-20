package com.projectardor.materials.resume.domain;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResumeAnalysisVersionTests {
    @Test void preservesExplicitScoringVersionWithoutRewritingLegacyDefaults() {
        var owner = UUID.randomUUID(); var resume = UUID.randomUUID();
        assertEquals("resume-analysis-v4", ResumeAnalysis.scored(owner, resume, Map.of(), "fixture", "key", "resume-analysis-v4").getPromptVersion());
        assertEquals("resume-analysis-v2", ResumeAnalysis.create(owner, resume, Map.of(), "fixture").getPromptVersion());
        assertThrows(IllegalArgumentException.class, () -> ResumeAnalysis.scored(owner, resume, Map.of(), "fixture", "key", ""));
    }
}
