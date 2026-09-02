package com.projectardor.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.agent.service.AgentMemoryService;
import com.projectardor.calendar.service.CalendarTaskService;
import com.projectardor.interview.service.InterviewService;
import com.projectardor.profile.service.ProfileService;
import com.projectardor.recap.service.InterviewRecapQueueService;
import com.projectardor.recap.service.InterviewRecapService;
import com.projectardor.resume.service.ResumeAnalysisQueueService;
import com.projectardor.resume.service.ResumeService;
import com.projectardor.websearch.service.TavilySearchService;

class CareerAgentToolsTests {

    @Test
    void deleteCalendarTaskUsesTrustedUserAndRequestedTask() {
        CalendarTaskService calendarTaskService = mock(CalendarTaskService.class);
        CareerAgentTools tools = new CareerAgentTools(
                mock(ResumeService.class),
                mock(ResumeAnalysisQueueService.class),
                mock(InterviewService.class),
                mock(ProfileService.class),
                mock(AgentMemoryService.class),
                calendarTaskService,
                mock(InterviewRecapService.class),
                mock(InterviewRecapQueueService.class),
                mock(TavilySearchService.class));
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        Map<String, Object> result = tools.bind(userId).deleteCalendarTask(taskId.toString());

        verify(calendarTaskService).delete(userId, taskId);
        assertThat(result).containsEntry("deleted", true).containsEntry("taskId", taskId);
    }
}
