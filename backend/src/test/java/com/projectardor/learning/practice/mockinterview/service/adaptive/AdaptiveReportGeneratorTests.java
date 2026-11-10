package com.projectardor.learning.practice.mockinterview.service.adaptive;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.learning.practice.mockinterview.domain.CompetencyState;
import com.projectardor.learning.practice.mockinterview.service.InterviewMetrics;
import com.projectardor.platform.model.llm.service.LlmGateway;

import tools.jackson.databind.json.JsonMapper;

/**
 * The report may only say what the persisted evidence supports. These tests pin the two halves of
 * that promise: Java owns the assessment and the boundary, and advice survives only when it names a
 * gap that was actually recorded on this candidate's answers.
 */
class AdaptiveReportGeneratorTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID owner = UUID.randomUUID();
    private final LlmGateway llm = mock(LlmGateway.class);
    private final AdaptiveReportGenerator generator =
            new AdaptiveReportGenerator(llm, new LlmJsonParser(mapper), mapper,
                    new InterviewMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static CompetencyProfileManager.Snapshot profile(String skill, int priority, Integer level,
            CompetencyState state, String... gaps) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String gap : gaps) {
            entries.add(Map.of("knowledgePoint", gap, "difficulty", 3, "detail", List.of("说不清" + gap)));
        }
        return new CompetencyProfileManager.Snapshot(skill, priority, level,
                level == null ? BigDecimal.ZERO : new BigDecimal("0.80"),
                entries.size(), state, List.of(), entries);
    }

    private static String recommendation(String competency, String point, int severity) {
        return "{\"competency\":\"" + competency + "\",\"knowledgePoint\":\"" + point
                + "\",\"severity\":" + severity + ",\"advice\":\"练" + point
                + "\",\"evidence\":\"第 2 题只答出定义\"}";
    }

    private static String report(String recommendations) {
        return "{\"overallScore\":72.5,\"jobFit\":\"基本匹配\",\"levelAssessment\":\"中级\","
                + "\"performanceSummary\":\"基础扎实，深度不足\",\"learningRecommendations\":[" + recommendations + "]}";
    }

    private void modelReturns(String json) {
        when(llm.completeJson(eq(owner), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult(json, "test-model"));
    }

    private AdaptiveReportGenerator.Input input(CompetencyProfileManager.Snapshot... profiles) {
        return new AdaptiveReportGenerator.Input("后端工程师", "Acme", "MID", List.of(profiles),
                List.of(Map.of("sequence", 1, "question", "讲讲 GC", "answer", "分代回收", "score", 2)));
    }

    @Test void theCompetencyAssessmentComesFromTheProfileNotFromTheModel() {
        modelReturns("{\"overallScore\":72.5,\"jobFit\":\"匹配\",\"levelAssessment\":\"中级\","
                + "\"performanceSummary\":\"不错\",\"competencyAssessments\":[{\"competency\":\"JVM\",\"estimatedLevel\":5}],"
                + "\"knowledgeBoundary\":[{\"competency\":\"JVM\",\"knowledgePoint\":\"编出来的缺口\"}],"
                + "\"learningRecommendations\":[]}");

        var content = generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED, "GC 选型"))).content();

        assertThat(entries(content.get("competencyAssessments"))).singleElement()
                .satisfies(entry -> assertThat(entry).containsEntry("competency", "JVM")
                        .containsEntry("estimatedLevel", 3).containsEntry("state", "VERIFIED"));
        assertThat(entries(content.get("knowledgeBoundary"))).singleElement()
                .satisfies(entry -> assertThat(entry).containsEntry("knowledgePoint", "GC 选型"));
    }

    @Test void adviceWithoutARecordedGapIsDropped() {
        modelReturns(report(recommendation("JVM", "GC 选型", 5) + "," + recommendation("JVM", "类加载", 5)));

        var content = generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED, "GC 选型"))).content();

        assertThat(entries(content.get("learningRecommendations"))).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("knowledgePoint", "GC 选型"));
    }

    @Test void theSameKnowledgePointInAnotherCompetencyIsNotTheSameEvidence() {
        modelReturns(report(recommendation("Spring", "GC 选型", 5)));

        var content = generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED, "GC 选型"))).content();

        assertThat(entries(content.get("learningRecommendations"))).isEmpty();
    }

    @Test void adviceWithoutConcreteEvidenceIsDropped() {
        modelReturns(report("{\"competency\":\"JVM\",\"knowledgePoint\":\"GC 选型\",\"severity\":5,"
                + "\"advice\":\"多练\",\"evidence\":\"\"}"));

        var content = generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED, "GC 选型"))).content();

        assertThat(entries(content.get("learningRecommendations"))).isEmpty();
    }

    @Test void survivingAdviceIsOrderedByJdPriorityBeforeSeverity() {
        modelReturns(report(String.join(",",
                recommendation("Spring", "事务传播", 5),
                recommendation("JVM", "GC 选型", 2),
                recommendation("JVM", "内存模型", 5))));

        var content = generator.generate(owner, input(
                profile("Spring", 3, 2, CompetencyState.GAP_EXPOSED, "事务传播"),
                profile("JVM", 5, 3, CompetencyState.VERIFIED, "GC 选型", "内存模型"))).content();

        assertThat(entries(content.get("learningRecommendations")))
                .extracting(item -> item.get("competency") + "/" + item.get("knowledgePoint"))
                .containsExactly("JVM/内存模型", "JVM/GC 选型", "Spring/事务传播");
    }

    @Test void severityIsClampedToTheOneToFiveBand() {
        modelReturns(report(String.join(",",
                recommendation("JVM", "GC 选型", 99), recommendation("JVM", "内存模型", -3))));

        var content = generator.generate(owner,
                input(profile("JVM", 5, 3, CompetencyState.VERIFIED, "GC 选型", "内存模型"))).content();

        assertThat(entries(content.get("learningRecommendations")))
                .extracting(item -> item.get("severity"))
                .containsExactly(5, 1);
    }

    @Test void anUntestedCompetencyIsReportedAsUnverifiedNotAsInability() {
        modelReturns(report(""));

        var content = generator.generate(owner, input(profile("Kubernetes", 4, null, CompetencyState.UNVERIFIED))).content();

        assertThat(entries(content.get("competencyAssessments"))).singleElement()
                .satisfies(entry -> assertThat(entry).containsEntry("state", "UNVERIFIED")
                        .containsEntry("estimatedLevel", null).containsEntry("evidenceCount", 0));
        assertThat(entries(content.get("knowledgeBoundary"))).isEmpty();
    }

    @Test void thePromptCarriesTheBoundaryAndTheAnswerTranscript() {
        modelReturns(report(""));

        generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED, "GC 选型")));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).completeJson(eq(owner), anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains("GC 选型").contains("分代回收").contains("后端工程师")
                .contains("knowledgeBoundary").contains("competencyAssessments");
    }

    @Test void theSystemPromptForbidsTurningUntestedIntoUnable() {
        modelReturns(report(""));

        generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED)));

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(llm).completeJson(eq(owner), system.capture(), anyString());
        assertThat(system.getValue()).contains("UNVERIFIED").contains("不能写成候选人不会");
    }

    @Test void oneMalformedReportGetsExactlyOneRepairCall() {
        when(llm.completeJson(eq(owner), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult("{\"jobFit\":\"匹配\"}", "test-model"))
                .thenReturn(new LlmGateway.LlmResult(report(""), "test-model"));

        var result = generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED)));

        assertThat(result.overallScore()).isEqualByComparingTo("72.50");
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void aSecondMalformedReportFailsInsteadOfLooping() {
        modelReturns("{\"overallScore\":\"很高\"}");

        assertThatThrownBy(() -> generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED))))
                .isInstanceOf(IllegalStateException.class);
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void anOutOfRangeOverallScoreIsRefused() {
        modelReturns("{\"overallScore\":120,\"jobFit\":\"匹配\",\"levelAssessment\":\"高级\",\"performanceSummary\":\"好\"}");

        assertThatThrownBy(() -> generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("0 到 100");
    }

    @Test void theOverallScoreIsStoredWithTwoDecimals() {
        modelReturns(report("").replace("72.5", "72.567"));

        assertThat(generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED))).overallScore())
                .isEqualTo(new BigDecimal("72.57"));
    }

    @Test void theRecommendationListIsCapped() {
        List<String> gaps = new ArrayList<>();
        List<String> advice = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            gaps.add("知识点" + index);
            advice.add(recommendation("JVM", "知识点" + index, 3));
        }
        modelReturns(report(String.join(",", advice)));

        var content = generator.generate(owner,
                input(profile("JVM", 5, 3, CompetencyState.VERIFIED, gaps.toArray(String[]::new)))).content();

        assertThat(entries(content.get("learningRecommendations"))).hasSize(12);
    }

    @Test void theReportIsTaggedAdaptiveAndKeepsTheModelName() {
        modelReturns(report(""));

        var result = generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED)));

        assertThat(result.content()).containsEntry("reportKind", "ADAPTIVE")
                .containsEntry("jobFit", "基本匹配").containsEntry("levelAssessment", "中级");
        assertThat(result.modelName()).isEqualTo("test-model");
        assertThat(AdaptiveReportGenerator.PROMPT_VERSION).isEqualTo("adaptive-interview-report-v1");
    }

    @Test void overlongProseIsTrimmedRatherThanStoredWhole() {
        String longText = "评".repeat(3000);
        modelReturns(report("").replace("基本匹配", longText));

        var content = generator.generate(owner, input(profile("JVM", 5, 3, CompetencyState.VERIFIED))).content();

        assertThat(String.valueOf(content.get("jobFit"))).hasSize(2000);
    }
}