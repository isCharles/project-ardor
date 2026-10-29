package com.projectardor.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.automation.service.AutomationService;
import com.projectardor.assistant.memory.service.AgentMemoryService;
import com.projectardor.assistant.memory.service.MemoryEmbeddingService;
import com.projectardor.jobsearch.applicationactivity.service.ApplicationRhythmService;
import com.projectardor.jobsearch.interview.service.OpportunityService;
import com.projectardor.jobsearch.record.service.InterviewRecapQueueService;
import com.projectardor.jobsearch.record.service.InterviewRecapService;
import com.projectardor.learning.plan.service.LearningPlanService;
import com.projectardor.learning.practice.mockinterview.service.InterviewService;
import com.projectardor.learning.review.service.FlashcardService;
import com.projectardor.materials.knowledge.service.KnowledgeService;
import com.projectardor.materials.latex.service.LatexWorkspaceService;
import com.projectardor.materials.resume.service.ResumeAnalysisQueueService;
import com.projectardor.materials.resume.service.ResumeService;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.platform.jobs.aijobs.AiJobService;
import com.projectardor.platform.model.websearch.service.TavilySearchService;
import com.projectardor.scheduling.advice.service.CalendarAdviceService;
import com.projectardor.scheduling.service.CalendarTaskService;
import com.projectardor.scheduling.service.TaskSeriesService;

class LatexWorkspaceContextTests {

    @Test
    void latexContextNamesTheOwnedWorkspaceAndItsCurrentRevision() {
        UUID user = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        LatexWorkspaceService latex = mock(LatexWorkspaceService.class);
        when(latex.read(user, projectId, null)).thenReturn(new LatexWorkspaceService.Snapshot(
                projectId, "我的简历", "main.tex", 3, Map.of("main.tex", "x"), "sha", 2, null, Instant.now()));
        CareerAgentTools tools = new CareerAgentTools(
                mock(ResumeService.class), mock(ResumeAnalysisQueueService.class), mock(InterviewService.class),
                mock(ProfileService.class), mock(AgentMemoryService.class), mock(CalendarTaskService.class),
                mock(TaskSeriesService.class), mock(InterviewRecapService.class), mock(InterviewRecapQueueService.class),
                mock(TavilySearchService.class), mock(KnowledgeService.class), mock(LearningPlanService.class),
                mock(ApplicationRhythmService.class), mock(AutomationService.class), mock(CalendarAdviceService.class),
                mock(OpportunityService.class), mock(AiJobService.class),
                mock(com.projectardor.assistant.execution.service.AgentExecutionAuditStore.class),
                mock(FlashcardService.class), mock(MemoryEmbeddingService.class), latex);

        String hint = tools.contextHint(user, "latex", projectId);

        assertThat(hint).contains("我的简历", projectId.toString(), "read_latex_project", "不要当成版本号");
        assertThat(hint).doesNotContain("第 3 版");
        assertThat(tools.resolveContext(user, "LATEX", projectId).label()).isEqualTo("我的简历");
    }
}
