package com.projectardor.learning.practice.mockinterview.service.adaptive;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.jobsearch.interview.service.InterviewProcessView;
import com.projectardor.jobsearch.interview.service.OpportunityService;
import com.projectardor.learning.practice.mockinterview.domain.InterviewModality;
import com.projectardor.learning.practice.mockinterview.domain.InterviewPlan;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.repository.InterviewPlanRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewSessionRepository;
import com.projectardor.learning.practice.mockinterview.service.InterviewMetrics;
import com.projectardor.learning.practice.mockinterview.service.LeetCodeHot100;
import com.projectardor.materials.resume.service.ResumeService;
import com.projectardor.platform.model.llm.service.LlmGateway;

import tools.jackson.databind.json.JsonMapper;

/**
 * The model proposes competencies; Java decides which survive and what order they are asked in.
 * A resume mention is recorded as a hypothesis and nothing more — the profile stays unverified
 * until an answer proves something.
 */
class InterviewPlannerTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID owner = UUID.randomUUID();
    private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    private final InterviewPlanRepository plans = mock(InterviewPlanRepository.class);
    private final OpportunityService opportunities = mock(OpportunityService.class);
    private final LlmGateway llm = mock(LlmGateway.class);
    private final InterviewPlanner planner = new InterviewPlanner(sessions, plans,
            mock(ResumeService.class), opportunities, llm, new LlmJsonParser(mapper), mapper,
            new InterviewMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            mock(PlatformTransactionManager.class));

    private InterviewSession session;

    private void givenAdaptiveSession() {
        session = InterviewSession.createAdaptive(owner, null, "Acme", "后端工程师", null,
                "MID", 30, false, "负责高并发后端");
        when(sessions.findByIdAndUserId(session.getId(), owner)).thenReturn(Optional.of(session));
        when(sessions.findOwnedForUpdate(session.getId(), owner)).thenReturn(Optional.of(session));
        when(plans.findByInterviewSessionIdAndUserId(session.getId(), owner)).thenReturn(Optional.empty());
        when(plans.save(any(InterviewPlan.class))).thenAnswer(call -> call.getArgument(0));
    }

    private InterviewPlan storedPlan(String modelName) {
        return InterviewPlan.create(owner, session.getId(),
                Map.of("competencies", List.of(
                        Map.of("skill", "数据库", "jdPriority", 5, "initialDifficulty", 3,
                                "knowledgePoints", List.of("索引")),
                        Map.of("skill", "并发", "jdPriority", 4, "initialDifficulty", 3,
                                "knowledgePoints", List.of("锁")),
                        Map.of("skill", "网络", "jdPriority", 3, "initialDifficulty", 2,
                                "knowledgePoints", List.of("TCP")))),
                modelName, InterviewPlanner.PROMPT_VERSION);
    }

    private LlmGateway.LlmResult result(String json) {
        return new LlmGateway.LlmResult(json, "test-model");
    }

    private static String competency(String skill, int priority, int difficulty, String points) {
        return "{\"skill\":\"" + skill + "\",\"jdPriority\":" + priority + ",\"initialDifficulty\":" + difficulty
                + ",\"questionType\":\"TECHNICAL\",\"knowledgePoints\":[" + points + "],"
                + "\"resumeMentioned\":false,\"rationale\":\"岗位需要\"}";
    }

    private static String plan(String... competencies) {
        return "{\"competencies\":[" + String.join(",", competencies) + "]}";
    }

    private static String threeCompetencies() {
        return plan(competency("数据库", 5, 3, "\"索引\""),
                competency("并发", 4, 3, "\"锁\""),
                competency("网络", 3, 2, "\"TCP\""));
    }

    @Test void theAskingOrderFollowsJdPriorityNotTheOrderTheModelHappenedToUse() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(plan(
                competency("网络", 2, 2, "\"TCP\""),
                competency("数据库", 5, 3, "\"索引\""),
                competency("并发", 4, 3, "\"锁\""))));
        assertThat(planner.plan(owner, session.getId()).competencies())
                .extracting(InterviewPlanner.CompetencyTarget::skill)
                .containsExactly("数据库", "并发", "网络");
    }

    @Test void theSameCompetencyIsOnlyAskedOnce() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(plan(
                competency("数据库", 5, 3, "\"索引\""),
                competency(" 数据库 ", 4, 2, "\"锁\""),
                competency("并发", 4, 3, "\"锁\""),
                competency("网络", 3, 2, "\"TCP\""))));
        assertThat(planner.plan(owner, session.getId()).competencies())
                .extracting(InterviewPlanner.CompetencyTarget::skill).containsExactly("数据库", "并发", "网络");
    }

    @Test void aCompetencyWithoutAKnowledgePointIsDropped() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(plan(
                competency("数据库", 5, 3, "\"索引\""),
                competency("凑数的", 5, 3, ""),
                competency("并发", 4, 3, "\"锁\""),
                competency("网络", 3, 2, "\"TCP\""))));
        assertThat(planner.plan(owner, session.getId()).competencies()).hasSize(3);
    }

    @Test void priorityAndDifficultyOutsideTheScaleAreClampedRatherThanStored() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(plan(
                competency("数据库", 9, 0, "\"索引\""),
                competency("并发", -1, 7, "\"锁\""),
                competency("网络", 3, 2, "\"TCP\""))));
        var competencies = planner.plan(owner, session.getId()).competencies();
        assertThat(competencies).allSatisfy(target -> {
            assertThat(target.jdPriority()).isBetween(1, 5);
            assertThat(target.initialDifficulty()).isBetween(1, 5);
        });
        assertThat(competencies).filteredOn(target -> target.skill().equals("数据库"))
                .singleElement().satisfies(target -> {
                    assertThat(target.jdPriority()).isEqualTo(5);
                    assertThat(target.initialDifficulty()).isEqualTo(1);
                });
    }

    @Test void tooFewUsableCompetenciesTriggerExactlyOneRepair() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString()))
                .thenReturn(result(plan(competency("数据库", 5, 3, "\"索引\""))), result(threeCompetencies()));
        assertThat(planner.plan(owner, session.getId()).competencies()).hasSize(3);
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void aSecondUnusablePlanFailsWithoutAThirdCall() {
        givenAdaptiveSession();
        String thin = plan(competency("数据库", 5, 3, "\"索引\""));
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(thin), result(thin));
        assertThatThrownBy(() -> planner.plan(owner, session.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("有效能力维度不足");
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
        verify(plans, never()).save(any());
    }

    @Test void anInterruptedInterviewReloadsItsPlanWithoutPayingForAnotherOne() {
        givenAdaptiveSession();
        var stored = storedPlan("earlier-model");
        when(plans.findByInterviewSessionIdAndUserId(session.getId(), owner)).thenReturn(Optional.of(stored));
        var plan = planner.plan(owner, session.getId());
        assertThat(plan.competencies()).hasSize(3);
        assertThat(plan.modelName()).isEqualTo("earlier-model");
        verifyNoInteractions(llm);
        verify(plans, never()).save(any());
    }

    @Test void planCreationTakesTheSessionRowLockBeforeInserting() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(threeCompetencies()));
        planner.plan(owner, session.getId());
        var order = inOrder(sessions, plans);
        order.verify(sessions).findByIdAndUserId(session.getId(), owner);
        order.verify(sessions).findOwnedForUpdate(session.getId(), owner);
        order.verify(plans).save(any(InterviewPlan.class));
    }

    @Test void aPlanStoredByAnOverlappingCallIsReturnedInsteadOfInsertedAgain() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(threeCompetencies()));
        when(plans.findByInterviewSessionIdAndUserId(session.getId(), owner))
                .thenReturn(Optional.empty(), Optional.of(storedPlan("other-model")));
        assertThat(planner.plan(owner, session.getId()).modelName()).isEqualTo("other-model");
        verify(sessions).findOwnedForUpdate(session.getId(), owner);
        verify(plans, never()).save(any());
    }

    @Test void aNullCompetencyEntryGetsTheSameSingleRepairAsAnyOtherInvalidShape() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString()))
                .thenReturn(result("{\"competencies\":[null,null,null]}"), result(threeCompetencies()));
        assertThat(planner.plan(owner, session.getId()).competencies()).hasSize(3);
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void aScalarCompetencyEntryIsRepairedRatherThanCrashingTheConversion() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString()))
                .thenReturn(result("{\"competencies\":[\"数据库\",42,[]]}"), result(threeCompetencies()));
        assertThat(planner.plan(owner, session.getId()).competencies()).hasSize(3);
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
    }

    @Test void aSecondMalformedPlanFailsWithoutAThirdCall() {
        givenAdaptiveSession();
        var malformed = result("{\"competencies\":[null]}");
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(malformed, malformed);
        assertThatThrownBy(() -> planner.plan(owner, session.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JSON 对象");
        verify(llm, times(2)).completeJson(eq(owner), anyString(), anyString());
        verify(plans, never()).save(any());
    }

    @Test void codingKnowledgePointsComeFromTheBankNotFromTheModel() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(plan(
                competency("数据库", 5, 3, "\"索引\""),
                competency("并发", 4, 3, "\"锁\""),
                competency("算法", 3, 3, "\"缓存淘汰策略\"").replace("\"TECHNICAL\"", "\"CODING\""))));
        var coding = planner.plan(owner, session.getId()).competencies().stream()
                .filter(target -> target.questionType().equals("CODING")).findFirst().orElseThrow();
        assertThat(coding.knowledgePoints()).containsExactlyElementsOf(LeetCodeHot100.CODING_TOPICS);
    }

    @Test void aCodingCompetencySurvivesEvenWhenTheModelLeftItsKnowledgePointsEmpty() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(plan(
                competency("数据库", 5, 3, "\"索引\""),
                competency("并发", 4, 3, "\"锁\""),
                competency("算法", 3, 3, "").replace("\"TECHNICAL\"", "\"CODING\""))));
        assertThat(planner.plan(owner, session.getId()).competencies())
                .extracting(InterviewPlanner.CompetencyTarget::skill)
                .containsExactly("数据库", "并发", "算法");
    }

    @Test void aResumeMentionIsRecordedAsAHypothesisAndNothingElse() {
        givenAdaptiveSession();
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(plan(
                competency("数据库", 5, 4, "\"索引\"").replace("\"resumeMentioned\":false", "\"resumeMentioned\":true"),
                competency("并发", 4, 3, "\"锁\""),
                competency("网络", 3, 2, "\"TCP\""))));
        var target = planner.plan(owner, session.getId()).competencies().get(0);
        assertThat(target.resumeMentioned()).isTrue();
        assertThat(target.toMap().keySet()).containsExactlyInAnyOrder("skill", "jdPriority", "initialDifficulty",
                "questionType", "knowledgePoints", "resumeMentioned", "rationale");
    }

    @Test void theJobDescriptionFallsBackToTheLinkedOpportunity() {
        session = InterviewSession.createAdaptive(owner, null, "Acme", "后端工程师", UUID.randomUUID(),
                "MID", 30, false, null);
        when(sessions.findByIdAndUserId(session.getId(), owner)).thenReturn(Optional.of(session));
        when(sessions.findOwnedForUpdate(session.getId(), owner)).thenReturn(Optional.of(session));
        when(plans.findByInterviewSessionIdAndUserId(session.getId(), owner)).thenReturn(Optional.empty());
        when(plans.save(any(InterviewPlan.class))).thenAnswer(call -> call.getArgument(0));
        when(opportunities.detail(owner, session.getJobOpportunityId())).thenReturn(new InterviewProcessView(
                session.getJobOpportunityId(), "Acme", "后端工程师", "BOSS", "ACTIVE",
                "岗位要求正文来自投递记录", null, 1, List.of(), Instant.now(), Instant.now()));
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(result(threeCompetencies()));

        planner.plan(owner, session.getId());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).completeJson(eq(owner), anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains("岗位要求正文来自投递记录");
    }

    @Test void aFixedPaperSessionHasNoCompetencyPlan() {
        var classic = InterviewSession.create(owner, null, InterviewModality.TEXT, null, "后端工程师", null);
        when(sessions.findByIdAndUserId(classic.getId(), owner)).thenReturn(Optional.of(classic));
        assertThatThrownBy(() -> planner.plan(owner, classic.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("固定题目");
        verifyNoInteractions(llm, plans);
    }
}