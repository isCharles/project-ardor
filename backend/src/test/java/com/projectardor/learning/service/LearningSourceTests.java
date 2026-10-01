package com.projectardor.learning.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.calendar.service.CalendarTaskService;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.learning.repository.LearningPlanRepository;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.profile.service.ProfileService;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;
import com.projectardor.recap.repository.InterviewRecapRepository;

import tools.jackson.databind.ObjectMapper;

class LearningSourceTests {
    @Test
    void foreignRecapCannotBeClaimedAsLearningEvidence() {
        UUID userId = UUID.randomUUID();
        UUID foreignQuestionId = UUID.randomUUID();
        InterviewRecapRepository recaps = mock(InterviewRecapRepository.class);
        InterviewRecapQuestionRepository questions = mock(InterviewRecapQuestionRepository.class);
        LlmGateway llm = mock(LlmGateway.class);
        when(recaps.findByIdAndUserId(foreignQuestionId, userId)).thenReturn(Optional.empty());
        when(questions.findByIdAndUserId(foreignQuestionId, userId)).thenReturn(Optional.empty());
        LearningPlanService service = new LearningPlanService(mock(LearningPlanRepository.class), llm,
                mock(LlmJsonParser.class), mock(ObjectMapper.class), mock(CalendarTaskService.class),
                mock(ProfileService.class), recaps, questions);

        assertThatThrownBy(() -> service.create(userId, "JVM", "面试薄弱点",
                LearningSourceType.RECAP, foreignQuestionId, Instant.now()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(llm, never()).completeJson(any(), any(), any());
    }
}
