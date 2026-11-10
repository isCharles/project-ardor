package com.projectardor.learning.practice.mockinterview.service.adaptive;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.learning.practice.mockinterview.domain.AdaptiveRubric;
import com.projectardor.learning.practice.mockinterview.domain.InterviewQuestion;
import com.projectardor.learning.practice.mockinterview.domain.QuestionOrigin;
import com.projectardor.learning.practice.mockinterview.domain.QuestionTarget;
import com.projectardor.learning.practice.mockinterview.repository.InterviewQuestionRepository;
import com.projectardor.learning.practice.mockinterview.service.InterviewMetrics;
import com.projectardor.learning.practice.mockinterview.service.LeetCodeHot100;
import com.projectardor.platform.model.llm.service.LlmGateway;

import tools.jackson.databind.json.JsonMapper;

/**
 * Whatever the model writes, the question that comes back is bound to the target the policy chose
 * and carries a full rubric. Coding questions skip the model entirely and come from the curated bank.
 */
class AdaptiveQuestionServiceTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID owner = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final LlmGateway llm = mock(LlmGateway.class);
    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final LeetCodeHot100 hot100 = mock(LeetCodeHot100.class);
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final AdaptiveQuestionService service =
            new AdaptiveQuestionService(llm, new LlmJsonParser(mapper), questions, hot100,
                    new InterviewMetrics(registry));

    private QuestionTarget target(QuestionOrigin origin, UUID parent) {
        return new QuestionTarget("数据库", "索引失效", 3, "TECHNICAL", origin, parent);
    }

    private LlmGateway.LlmResult result(String json) {
        return new LlmGateway.LlmResult(json, "test-model");
    }

    private static final String GOOD_QUESTION = """
            {"questionText":"什么情况下联合索引会失效？请举例说明。",
             "expectedPoints":["最左前缀原则","回表代价"],
             "depthMarkers":["覆盖索引取舍"],"commonMisconceptions":["加索引一定更快"]}
            """;

    @Test void theRubricIsCompleteBeforeTheQuestionLeavesTheService() {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(GOOD_QUESTION));
        var generated = service.generate(owner, session, target(QuestionOrigin.PLAN, null));
        var rubric = AdaptiveRubric.fromMap(generated.blueprint().rubric());
        assertThat(rubric.expectedPoints()).containsExactly("最左前缀原则", "回表代价");
        assertThat(rubric.scoreBands()).containsExactlyElementsOf(AdaptiveRubric.SCORE_BANDS);
        assertThat(generated.evaluationCriteria()).isEqualTo(rubric.expectedPoints());
    }

    @Test void theBlueprintKeepsTheTargetThePolicyChose() {
        UUID parent = UUID.randomUUID();
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(GOOD_QUESTION));
        var blueprint = service.generate(owner, session, target(QuestionOrigin.FOLLOW_UP, parent)).blueprint();
        assertThat(blueprint.competency()).isEqualTo("数据库");
        assertThat(blueprint.knowledgePoint()).isEqualTo("索引失效");
        assertThat(blueprint.difficulty()).isEqualTo(3);
        assertThat(blueprint.origin()).isEqualTo(QuestionOrigin.FOLLOW_UP);
        assertThat(blueprint.parentQuestionId()).isEqualTo(parent);
    }

    @Test void thePromptTellsTheModelWhatTheChosenDifficultyMeans() {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(GOOD_QUESTION));
        service.generate(owner, session, target(QuestionOrigin.PLAN, null));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).completeJson(eq(owner), anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains("索引失效").contains(AdaptiveRubric.DIFFICULTY_LEVELS.get(2));
    }

    @Test void theModelCannotRedefineTheScoreBands() {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result("""
                {"questionText":"什么情况下联合索引会失效？","expectedPoints":["最左前缀原则"],
                 "scoreBands":["0：随便","1：随便","2：随便","3：随便","4：随便"]}
                """));
        var rubric = AdaptiveRubric.fromMap(
                service.generate(owner, session, target(QuestionOrigin.PLAN, null)).blueprint().rubric());
        assertThat(rubric.scoreBands()).containsExactlyElementsOf(AdaptiveRubric.SCORE_BANDS);
    }

    @Test void aQuestionWithoutExpectedPointsTriggersExactlyOneRepair() {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(
                result("{\"questionText\":\"什么情况下联合索引会失效？\",\"expectedPoints\":[]}"),
                result(GOOD_QUESTION));
        assertThat(service.generate(owner, session, target(QuestionOrigin.PLAN, null))
                .blueprint().rubric()).containsKey("expectedPoints");
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void aBlankQuestionFailsClosedAfterOneRepair() {
        var blank = result("{\"questionText\":\"  \",\"expectedPoints\":[\"要点\"]}");
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(blank, blank);
        assertThatThrownBy(() -> service.generate(owner, session, target(QuestionOrigin.PLAN, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("题目正文");
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void aCodingQuestionComesFromTheBankWithoutSpendingAModelCall() {
        when(questions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(owner, session))
                .thenReturn(List.of());
        when(hot100.random()).thenReturn(new LeetCodeHot100.Problem(146, "lru-cache", "LRU Cache", "LRU 缓存"));
        var coding = new QuestionTarget("算法", "缓存淘汰策略", 3, "CODING", QuestionOrigin.PLAN, null);
        var generated = service.generate(owner, session, coding);
        assertThat(generated.questionType()).isEqualTo("CODING");
        assertThat(generated.leetcodeSlug()).isEqualTo("lru-cache");
        assertThat(generated.questionText()).contains("146").contains("LRU 缓存");
        assertThat(AdaptiveRubric.fromMap(generated.blueprint().rubric()).expectedPoints()).isNotEmpty();
        verifyNoInteractions(llm);
    }

    @Test void aCodingQuestionDoesNotRepeatAProblemAlreadyAskedInTheSession() {
        var used = InterviewQuestion.create(owner, session, 1, "旧编程题", "CODING", List.of("正确"), "two-sum");
        when(questions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(owner, session))
                .thenReturn(List.of(used));
        when(hot100.random()).thenReturn(LeetCodeHot100.PROBLEMS.get(0));
        var coding = new QuestionTarget("算法", "哈希查找", 3, "CODING", QuestionOrigin.PLAN, null);
        assertThat(service.generate(owner, session, coding).leetcodeSlug()).isNotEqualTo("two-sum");
    }

    @Test void aGeneratedQuestionBecomesAnEntityThatCarriesItsRubric() {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(GOOD_QUESTION));
        var generated = service.generate(owner, session, target(QuestionOrigin.PLAN, null));
        InterviewQuestion entity = generated.toEntity(owner, session, 4);
        assertThat(entity.getSequenceNumber()).isEqualTo(4);
        assertThat(entity.getDifficulty()).isEqualTo(3);
        assertThat(entity.getRubric()).containsKey("scoreBands");
        assertThat(entity.getOrigin()).isEqualTo(QuestionOrigin.PLAN);
    }

    @Test void questionLatencySeparatesTheBankFromTheModelAndRecordsTheChosenDifficulty() {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(new LlmGateway.LlmResult(
                GOOD_QUESTION, "test-model", new LlmGateway.Usage(900, 120)));
        when(hot100.random()).thenReturn(new LeetCodeHot100.Problem(146, "lru-cache", "LRU Cache", "LRU 缓存"));

        service.generate(owner, session, target(QuestionOrigin.FOLLOW_UP, null));
        service.generate(owner, session,
                new QuestionTarget("算法", "缓存淘汰策略", 4, "CODING", QuestionOrigin.PLAN, null));

        assertThat(registry.get("ardor.interview.question.duration")
                .tags("type", "TECHNICAL", "origin", "FOLLOW_UP").timer().count()).isEqualTo(1);
        assertThat(registry.get("ardor.interview.question.duration")
                .tags("type", "CODING", "origin", "PLAN").timer().count()).isEqualTo(1);
        assertThat(registry.get("ardor.interview.questions").tag("difficulty", "4").counter().count()).isEqualTo(1);
        assertThat(registry.get("ardor.interview.llm.tokens.input").tag("stage", "question")
                .counter().count()).isEqualTo(900);
    }
}