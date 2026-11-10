package com.projectardor.learning.practice.mockinterview.service.adaptive;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.learning.practice.mockinterview.domain.AdaptiveRubric;
import com.projectardor.learning.practice.mockinterview.domain.AnswerKind;
import com.projectardor.learning.practice.mockinterview.domain.InterviewQuestion;
import com.projectardor.learning.practice.mockinterview.domain.QuestionOrigin;
import com.projectardor.learning.practice.mockinterview.domain.QuestionTarget;
import com.projectardor.learning.practice.mockinterview.service.InterviewMetrics;
import com.projectardor.platform.model.llm.service.LlmGateway;

import tools.jackson.databind.json.JsonMapper;

/**
 * The evaluator is only allowed to read the rubric that was stored before the question was asked.
 * These tests pin the parts that stop a fluent model from inventing a higher score than the answer
 * earned: coverage by rubric index, verbatim quotes, and Java working out what was missed.
 */
class AnswerEvaluatorTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID owner = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final LlmGateway llm = mock(LlmGateway.class);
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final AnswerEvaluator evaluator = new AnswerEvaluator(llm, new LlmJsonParser(mapper),
            new InterviewMetrics(registry));

    private static final String ANSWER = "联合索引要遵守最左前缀原则，跳过最左列就用不上索引。"
            + "查询列不在索引里时需要回表代价很高，这时可以考虑覆盖索引避免回表。";

    private AdaptiveRubric rubric() {
        return AdaptiveRubric.create("索引失效", 3, List.of("最左前缀原则", "回表代价"),
                List.of("覆盖索引取舍"), List.of("加索引一定更快"));
    }

    private InterviewQuestion question(AdaptiveRubric rubric) {
        var target = new QuestionTarget("数据库", rubric.knowledgePoint(), rubric.difficulty(),
                "TECHNICAL", QuestionOrigin.PLAN, null);
        return InterviewQuestion.createAdaptive(owner, session, 1, "什么情况下联合索引会失效？",
                "TECHNICAL", rubric.expectedPoints(), target.withRubric(rubric), null);
    }

    private LlmGateway.LlmResult result(String json) {
        return new LlmGateway.LlmResult(json, "test-model");
    }

    private void replies(String first, String second) {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(first), result(second));
    }

    private void replies(String json) {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(json));
    }

    @Test void aQuestionWithoutARubricIsNeverScored() {
        var classic = InterviewQuestion.create(owner, session, 1, "介绍一下索引", "TECHNICAL", List.of("说清楚"));
        assertThatThrownBy(() -> evaluator.evaluate(owner, classic, ANSWER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("评分标准");
        verifyNoInteractions(llm);
    }

    @Test void coverageIsClaimedByRubricIndexAndJavaNamesThePoint() {
        replies("""
                {"answerKind":"ANSWER","score":2,"confidence":0.8,
                 "covered":[{"point":0,"quote":"最左前缀原则"}],"misconceptions":[],"knowledgeGaps":[]}
                """);
        var evaluation = evaluator.evaluate(owner, question(rubric()), ANSWER);
        assertThat(evaluation.coveredPoints()).singleElement()
                .satisfies(evidence -> assertThat(evidence.point()).isEqualTo("最左前缀原则"));
        assertThat(evaluation.score()).isEqualTo(2);
        assertThat(evaluation.modelName()).isEqualTo("test-model");
        assertThat(evaluation.promptVersion()).isEqualTo(AnswerEvaluator.PROMPT_VERSION);
    }

    @Test void javaComputesWhatWasMissedInsteadOfTrustingTheModel() {
        replies("""
                {"answerKind":"ANSWER","score":2,"confidence":0.8,
                 "covered":[{"point":0,"quote":"最左前缀原则"}],
                 "missingPoints":["模型自己编的缺口"],"knowledgeGaps":[]}
                """);
        assertThat(evaluator.evaluate(owner, question(rubric()), ANSWER).missingPoints())
                .containsExactly("回表代价");
    }

    @Test void aThreeIsRefusedUntilEveryExpectedPointIsCovered() {
        replies("""
                {"answerKind":"ANSWER","score":3,"confidence":0.9,
                 "covered":[{"point":0,"quote":"最左前缀原则"}],"knowledgeGaps":[]}
                """, """
                {"answerKind":"ANSWER","score":3,"confidence":0.9,
                 "covered":[{"point":0,"quote":"最左前缀原则"},{"point":1,"quote":"回表代价"}],"knowledgeGaps":[]}
                """);
        var evaluation = evaluator.evaluate(owner, question(rubric()), ANSWER);
        assertThat(evaluation.score()).isEqualTo(3);
        assertThat(evaluation.missingPoints()).isEmpty();
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void aFourNeedsDepthEvidenceCopiedFromTheAnswer() {
        String both = """
                {"point":0,"quote":"最左前缀原则"},{"point":1,"quote":"回表代价"}
                """;
        replies("{\"answerKind\":\"ANSWER\",\"score\":4,\"confidence\":0.9,\"covered\":[" + both
                + "],\"depthQuote\":\"\",\"knowledgeGaps\":[]}",
                "{\"answerKind\":\"ANSWER\",\"score\":4,\"confidence\":0.9,\"covered\":[" + both
                + "],\"depthQuote\":\"覆盖索引避免回表\",\"knowledgeGaps\":[]}");
        assertThat(evaluator.evaluate(owner, question(rubric()), ANSWER).depthQuote())
                .isEqualTo("覆盖索引避免回表");
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void aQuoteTheAnswerDoesNotContainIsNotEvidenceAndTheTurnStaysUnscored() {
        String invented = """
                {"answerKind":"ANSWER","score":2,"confidence":0.9,
                 "covered":[{"point":0,"quote":"候选人详细解释了B+树的结构"}],"knowledgeGaps":[]}
                """;
        replies(invented, invented);
        assertThatThrownBy(() -> evaluator.evaluate(owner, question(rubric()), ANSWER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不是答案原文");
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void aClarificationIsNeverScoredEvenWhenTheModelSendsAScore() {
        replies("""
                {"answerKind":"CLARIFICATION","score":4,"confidence":0.99,
                 "covered":[{"point":0,"quote":"最左前缀原则"}],"knowledgeGaps":["不该出现"]}
                """);
        var evaluation = evaluator.evaluate(owner, question(rubric()), "你说的联合索引是几个列的那种？");
        assertThat(evaluation.isClarification()).isTrue();
        assertThat(evaluation.score()).isNull();
        assertThat(evaluation.confidence()).isNull();
        assertThat(evaluation.coveredPoints()).isEmpty();
        assertThat(evaluation.missingPoints()).isEmpty();
        assertThat(evaluation.knowledgeGaps()).isEmpty();
        verify(llm, times(1)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void scoringLatencyAndTokensAreAttributedToTheEvaluateStage() {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(new LlmGateway.LlmResult("""
                {"answerKind":"ANSWER","score":3,"confidence":0.9,
                 "covered":[{"point":0,"quote":"最左前缀原则"},{"point":1,"quote":"回表代价"}],
                 "knowledgeGaps":[]}
                """, "test-model", new LlmGateway.Usage(1200, 80)));

        evaluator.evaluate(owner, question(rubric()), ANSWER);

        assertThat(registry.get("ardor.interview.evaluation.duration").tag("outcome", "scored")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("ardor.interview.llm.tokens.input").tag("stage", "evaluate")
                .counter().count()).isEqualTo(1200);
        assertThat(registry.get("ardor.interview.llm.tokens.output").tag("stage", "evaluate")
                .counter().count()).isEqualTo(80);
    }

    @Test void aClarificationIsTimedApartFromScoredAnswersSoItsLatencyIsNotReadAsScoring() {
        replies("""
                {"answerKind":"CLARIFICATION","score":4,"confidence":0.99,
                 "covered":[{"point":0,"quote":"最左前缀原则"}],"knowledgeGaps":["不该出现"]}
                """);

        evaluator.evaluate(owner, question(rubric()), "你说的联合索引是几个列的那种？");

        assertThat(registry.get("ardor.interview.evaluation.duration").tag("outcome", "clarification")
                .timer().count()).isEqualTo(1);
        assertThat(registry.find("ardor.interview.evaluation.duration").tag("outcome", "scored").timer()).isNull();
    }

    @Test void anAnswerThatCouldNotBeScoredIsCountedAsAFailedEvaluation() {
        var classic = InterviewQuestion.create(owner, session, 1, "介绍一下索引", "TECHNICAL", List.of("说清楚"));

        assertThatThrownBy(() -> evaluator.evaluate(owner, classic, ANSWER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("评分标准");

        assertThat(registry.get("ardor.interview.evaluation.duration").tag("outcome", "failed")
                .timer().count()).isEqualTo(1);
    }

    @Test void anExplicitIDontKnowIsZeroAndNamesTheKnowledgePoint() {
        replies("""
                {"answerKind":"UNKNOWN","score":0,"confidence":0.95,"covered":[],"knowledgeGaps":[]}
                """);
        var evaluation = evaluator.evaluate(owner, question(rubric()), "这个我不知道。");
        assertThat(evaluation.kind()).isEqualTo(AnswerKind.UNKNOWN);
        assertThat(evaluation.score()).isZero();
        assertThat(evaluation.knowledgeGaps()).containsExactly("索引失效");
        assertThat(evaluation.missingPoints()).containsExactly("最左前缀原则", "回表代价");
    }

    @Test void anExplicitIDontKnowCannotBeScoredAboveZero() {
        replies("""
                {"answerKind":"UNKNOWN","score":2,"confidence":0.9,"covered":[],"knowledgeGaps":[]}
                """, """
                {"answerKind":"UNKNOWN","score":0,"confidence":0.9,"covered":[],"knowledgeGaps":[]}
                """);
        assertThat(evaluator.evaluate(owner, question(rubric()), "不会").score()).isZero();
    }

    @Test void anOffTopicAnswerKeepsItsOwnKindSoPolicyCanRefuseToReadItAsAbility() {
        replies("""
                {"answerKind":"OFF_TOPIC","score":0,"confidence":0.9,"covered":[],"knowledgeGaps":[]}
                """);
        assertThat(evaluator.evaluate(owner, question(rubric()), "我想先聊聊薪资待遇").kind())
                .isEqualTo(AnswerKind.OFF_TOPIC);
    }

    @Test void confidenceOutsideZeroToOneIsRefused() {
        String bad = """
                {"answerKind":"ANSWER","score":2,"confidence":1.5,
                 "covered":[{"point":0,"quote":"最左前缀原则"}],"knowledgeGaps":[]}
                """;
        replies(bad, bad);
        assertThatThrownBy(() -> evaluator.evaluate(owner, question(rubric()), ANSWER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("置信度");
    }

    @Test void aScoreOutsideTheZeroToFourRubricIsRefused() {
        String bad = """
                {"answerKind":"ANSWER","score":7,"confidence":0.9,"covered":[],"knowledgeGaps":[]}
                """;
        replies(bad, bad);
        assertThatThrownBy(() -> evaluator.evaluate(owner, question(rubric()), ANSWER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("0 到 4");
    }

    @Test void aPointIndexOutsideTheRubricIsRefused() {
        String bad = """
                {"answerKind":"ANSWER","score":2,"confidence":0.9,
                 "covered":[{"point":7,"quote":"最左前缀原则"}],"knowledgeGaps":[]}
                """;
        replies(bad, bad);
        assertThatThrownBy(() -> evaluator.evaluate(owner, question(rubric()), ANSWER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("评分标准之外");
    }

    @Test void theSamePointCannotBeClaimedTwiceToInflateCoverage() {
        String bad = """
                {"answerKind":"ANSWER","score":2,"confidence":0.9,
                 "covered":[{"point":0,"quote":"最左前缀原则"},{"point":0,"quote":"回表代价"}],"knowledgeGaps":[]}
                """;
        replies(bad, bad);
        assertThatThrownBy(() -> evaluator.evaluate(owner, question(rubric()), ANSWER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("重复认领");
    }

    @Test void thePromptCarriesTheStoredBandsAndTheNumberedPoints() {
        Map<String, Object> stored = rubric().toMap();
        stored.put("scoreBands", List.of("旧0档", "旧1档", "旧2档", "旧3档", "旧4档"));
        var target = new QuestionTarget("数据库", "索引失效", 3, "TECHNICAL", QuestionOrigin.PLAN, null);
        var question = InterviewQuestion.createAdaptive(owner, session, 1, "什么情况下联合索引会失效？",
                "TECHNICAL", List.of("最左前缀原则"), target.withRubric(AdaptiveRubric.fromMap(stored)), null);
        replies("""
                {"answerKind":"ANSWER","score":2,"confidence":0.8,
                 "covered":[{"point":0,"quote":"最左前缀原则"}],"knowledgeGaps":[]}
                """);
        evaluator.evaluate(owner, question, ANSWER);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).completeJson(eq(owner), anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains("旧3档").contains("0. 最左前缀原则").contains("1. 回表代价")
                .doesNotContain(AdaptiveRubric.SCORE_BANDS.get(3));
    }
}