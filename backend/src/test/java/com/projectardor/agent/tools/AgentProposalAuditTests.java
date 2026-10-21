package com.projectardor.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.projectardor.agent.service.AgentExecutionAuditStore;
import com.projectardor.agent.service.AgentMemoryService;
import com.projectardor.jobsearch.applicationactivity.service.ApplicationRhythmService;
import com.projectardor.scheduling.service.CalendarTaskService;
import com.projectardor.scheduling.service.TaskSeriesService;
import com.projectardor.learning.practice.mockinterview.service.InterviewService;
import com.projectardor.materials.knowledge.service.KnowledgeService;
import com.projectardor.learning.plan.service.LearningPlanService;
import com.projectardor.jobsearch.interview.service.OpportunityService;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.jobsearch.record.service.InterviewRecapQueueService;
import com.projectardor.jobsearch.record.service.InterviewRecapService;
import com.projectardor.materials.resume.service.ResumeAnalysisQueueService;
import com.projectardor.materials.resume.service.ResumeService;
import com.projectardor.platform.model.websearch.service.TavilySearchService;

/** Proposal cards are stable, auditable entities; the legacy card key never changes. */
class AgentProposalAuditTests {

    @Test
    void learningProposalCarriesStableIdentityAndIsRecordedWithItsLegacyKey() {
        AgentExecutionAuditStore audit = mock(AgentExecutionAuditStore.class);
        CareerAgentTools tools = tools(audit);
        UUID user = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();

        var bound = tools.bind(user, "帮我复习 Redis", run).withConversationId(conversation);
        bound.createLearningPlan("Redis 持久化", "面试中没有说明 AOF", null, "RECAP", UUID.randomUUID().toString());
        var pending = bound.pendingConfirmations().getFirst();

        assertThat(pending.proposalId()).isNotNull();
        assertThat(pending.learningPlan()).isNotNull();
        // Re-reading the same run rebuilds the same stable proposal identity.
        var retried = tools.bind(user, "帮我复习 Redis", run).withConversationId(conversation);
        retried.createLearningPlan("Redis 持久化", "面试中没有说明 AOF", null, "RECAP",
                pending.learningPlan().sourceId() == null ? null : pending.learningPlan().sourceId().toString());
        assertThat(retried.pendingConfirmations()).hasSize(1);
        assertThat(retried.pendingConfirmations().getFirst().proposalId()).isEqualTo(pending.proposalId());

        ArgumentCaptor<AgentExecutionAuditStore.ProposalRecord> recorded =
                ArgumentCaptor.forClass(AgentExecutionAuditStore.ProposalRecord.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).recordProposal(recorded.capture());
        var first = recorded.getAllValues().getFirst();
        assertThat(first.proposalId()).isEqualTo(pending.proposalId());
        assertThat(first.kind()).isEqualTo("learning_plan");
        assertThat(first.context().userId()).isEqualTo(user);
        assertThat(first.context().conversationId()).isEqualTo(conversation);
        assertThat(first.context().runId()).isEqualTo(run);
        assertThat(first.legacyKey()).isEqualTo(pending.learningPlan().requestId().toString());
        assertThat(first.learningRequestId()).isEqualTo(pending.learningPlan().requestId());
    }

    @Test
    void deleteProposalUsesTheSameLegacyKeyOldClientsResolve() {
        AgentExecutionAuditStore audit = mock(AgentExecutionAuditStore.class);
        LearningPlanService plans = mock(LearningPlanService.class);
        CareerAgentTools tools = tools(audit, plans);
        UUID user = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        var plan = mock(com.projectardor.learning.plan.domain.LearningPlan.class);
        when(plan.getConcept()).thenReturn("JVM");
        when(plans.get(user, planId)).thenReturn(plan);

        var bound = tools.bind(user, "删除 JVM 学习", run).withConversationId(conversation);
        assertThat(bound.deleteLearningPlan(planId.toString())).containsEntry("status", "CONFIRMATION_REQUIRED");

        var pending = bound.pendingConfirmations().getFirst();
        ArgumentCaptor<AgentExecutionAuditStore.ProposalRecord> recorded =
                ArgumentCaptor.forClass(AgentExecutionAuditStore.ProposalRecord.class);
        verify(audit).recordProposal(recorded.capture());
        assertThat(recorded.getValue().proposalId()).isEqualTo(pending.proposalId());
        assertThat(recorded.getValue().kind()).isEqualTo("learning_plan_delete");
        assertThat(recorded.getValue().targetId()).isEqualTo(planId);
        assertThat(recorded.getValue().legacyKey()).isEqualTo("learning_plan_delete:/api/learning-plans/" + planId);
        verify(plans, never()).delete(any(), any());
    }

    @Test
    void legacySynchronousChatStillReturnsCardsButRecordsNoRunAudit() {
        AgentExecutionAuditStore audit = mock(AgentExecutionAuditStore.class);
        CareerAgentTools tools = tools(audit);
        UUID user = UUID.randomUUID();
        // No durable run identity (legacy sync chat): the card stays usable, no audit row is invented.
        var bound = tools.bind(user, "帮我复习 Redis").withConversationId(UUID.randomUUID());
        bound.createLearningPlan("Redis 持久化", null, null, "AGENT", null);
        assertThat(bound.pendingConfirmations()).singleElement()
                .satisfies(pending -> assertThat(pending.proposalId()).isNotNull());
        verify(audit, never()).recordProposal(any());
    }

    private static CareerAgentTools tools(AgentExecutionAuditStore audit) {
        return tools(audit, mock(LearningPlanService.class));
    }

    private static CareerAgentTools tools(AgentExecutionAuditStore audit, LearningPlanService plans) {
        return new CareerAgentTools(
                mock(ResumeService.class), mock(ResumeAnalysisQueueService.class), mock(InterviewService.class),
                mock(ProfileService.class), mock(AgentMemoryService.class), mock(CalendarTaskService.class),
                mock(TaskSeriesService.class), mock(InterviewRecapService.class), mock(InterviewRecapQueueService.class),
                mock(TavilySearchService.class), mock(KnowledgeService.class), plans,
                mock(ApplicationRhythmService.class), mock(OpportunityService.class),
                mock(com.projectardor.platform.jobs.aijobs.AiJobService.class), audit, mock(com.projectardor.learning.review.service.FlashcardService.class));
    }
}
