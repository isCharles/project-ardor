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
    private final LeetCodeHot100 hot100 = new LeetCodeHot100();
    private final AdaptiveQuestionService service =
            new AdaptiveQuestionService(llm, new LlmJsonParser(mapper), questions, hot100);

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
        var coding = new QuestionTarget("算法", "链表", 3, "CODING", QuestionOrigin.PLAN, null);
        var generated = service.generate(owner, session, coding);
        assertThat(generated.questionType()).isEqualTo("CODING");
        assertThat(generated.blueprint().knowledgePoint()).isEqualTo("链表");
        assertThat(generated.questionText()).contains("LeetCode").contains("思路、时间与空间复杂度");
        assertThat(AdaptiveRubric.fromMap(generated.blueprint().rubric()).expectedPoints()).isNotEmpty();
        verifyNoInteractions(llm);
    }

    @Test void aCodingQuestionOnlyEverPicksAProblemInsideTheTargetedTopic() {
        when(questions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(owner, session))
                .thenReturn(List.of());
        var coding = new QuestionTarget("算法", "链表", 3, "CODING", QuestionOrigin.PLAN, null);
        List<String> allowed = hot100.byTopic("链表").stream().map(LeetCodeHot100.Problem::slug).toList();
        for (int attempt = 0; attempt < 50; attempt++) {
            assertThat(allowed).contains(service.generate(owner, session, coding).leetcodeSlug());
        }
    }

    @Test void aCodingTargetTheBankCannotServeIsRefusedInsteadOfMislabelled() {
        var coding = new QuestionTarget("算法", "缓存淘汰策略", 3, "CODING", QuestionOrigin.PLAN, null);
        assertThatThrownBy(() -> service.generate(owner, session, coding))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不在题库范围内");
        verifyNoInteractions(llm);
    }

    @Test void aCodingQuestionDoesNotRepeatAProblemAlreadyAskedInTheSession() {
        var used = InterviewQuestion.create(owner, session, 1, "旧编程题", "CODING", List.of("正确"), "two-sum");
        var alsoUsed = InterviewQuestion.create(owner, session, 2, "旧编程题", "CODING", List.of("正确"),
                "group-anagrams");
        when(questions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(owner, session))
                .thenReturn(List.of(used, alsoUsed));
        var coding = new QuestionTarget("算法", "哈希", 3, "CODING", QuestionOrigin.PLAN, null);
        assertThat(service.generate(owner, session, coding).leetcodeSlug())
                .isEqualTo("longest-consecutive-sequence");
    }

    @Test void aTopicWithNoProblemLeftIsRefusedRatherThanRepeatingOne() {
        List<InterviewQuestion> used = hot100.byTopic("哈希").stream()
                .map(problem -> InterviewQuestion.create(owner, session, 1, "旧编程题", "CODING",
                        List.of("正确"), problem.slug()))
                .toList();
        when(questions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(owner, session)).thenReturn(used);
        var coding = new QuestionTarget("算法", "哈希", 3, "CODING", QuestionOrigin.PLAN, null);
        assertThatThrownBy(() -> service.generate(owner, session, coding))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已全部问过");
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
}