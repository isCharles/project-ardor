package com.projectardor.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.interview.domain.InterviewModality;
import com.projectardor.interview.domain.InterviewQuestion;
import com.projectardor.interview.repository.InterviewAnswerRepository;
import com.projectardor.interview.repository.InterviewEvaluationRepository;
import com.projectardor.interview.repository.InterviewQuestionRepository;
import com.projectardor.interview.repository.InterviewSessionRepository;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.profile.domain.UserProfile;
import com.projectardor.profile.service.ProfileService;
import com.projectardor.resume.service.ResumeService;

import tools.jackson.databind.ObjectMapper;

class InterviewCodingQuestionTests {
    private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final ProfileService profiles = mock(ProfileService.class);
    private final LlmGateway llm = mock(LlmGateway.class);
    private final LeetCodeHot100 hot100 = mock(LeetCodeHot100.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final InterviewService service = new InterviewService(sessions, questions,
            mock(InterviewAnswerRepository.class), mock(InterviewEvaluationRepository.class), profiles,
            mock(ResumeService.class), llm, new LlmJsonParser(mapper), mapper,
            mock(PlatformTransactionManager.class), hot100);

    private static final LeetCodeHot100.Problem LRU = LeetCodeHot100.find("lru-cache").orElseThrow();

    @Test
    void technicalInterviewReplacesModelCodingQuestionWithHot100Problem() {
        List<InterviewQuestion> saved = create("Java 后端工程师", """
                {"questions":[
                  {"questionText":"项目里最难的问题？","questionType":"PROJECT","evaluationCriteria":[]},
                  {"questionText":"实现一个自创算法","questionType":"CODING","evaluationCriteria":[]},
                  {"questionText":"另一道自创算法","questionType":"CODING","evaluationCriteria":[]}]}
                """);

        assertThat(saved.get(1).getQuestionType()).isEqualTo("CODING");
        assertThat(saved.get(1).getLeetcodeSlug()).isEqualTo("lru-cache");
        assertThat(saved.get(1).getQuestionText()).contains("146", "LRU");
        // Only the curated problem stays a coding question.
        assertThat(saved.get(2).getQuestionType()).isEqualTo("TECHNICAL");
        assertThat(saved.get(2).getLeetcodeSlug()).isNull();
    }

    @Test
    void technicalInterviewWithoutCodingUsesLastSlot() {
        List<InterviewQuestion> saved = create("software engineer", """
                {"questions":[
                  {"questionText":"A","questionType":"TECHNICAL","evaluationCriteria":[]},
                  {"questionText":"B","questionType":"PROJECT","evaluationCriteria":[]},
                  {"questionText":"C","questionType":"BEHAVIORAL","evaluationCriteria":[]}]}
                """);

        assertThat(saved).extracting(InterviewQuestion::getLeetcodeSlug).containsExactly(null, null, "lru-cache");
    }

    @Test
    void nonTechnicalInterviewHasNoCodingQuestion() {
        List<InterviewQuestion> saved = create("产品经理", """
                {"questions":[
                  {"questionText":"A","questionType":"BEHAVIORAL","evaluationCriteria":[]},
                  {"questionText":"B","questionType":"CODING","evaluationCriteria":[]},
                  {"questionText":"C","questionType":"PROJECT","evaluationCriteria":[]}]}
                """);

        assertThat(saved).extracting(InterviewQuestion::getLeetcodeSlug).containsOnlyNulls();
        assertThat(saved).extracting(InterviewQuestion::getQuestionType).doesNotContain("CODING");
    }

    @Test
    void promptNeverAsksTheModelForPlaceholderQuestions() {
        create("产品经理", """
                {"questions":[
                  {"questionText":"A","questionType":"BEHAVIORAL","evaluationCriteria":[]},
                  {"questionText":"B","questionType":"PROJECT","evaluationCriteria":[]},
                  {"questionText":"C","questionType":"TECHNICAL","evaluationCriteria":[]}]}
                """);

        // Non-technical roles never get a Hot 100 replacement, so a model-written placeholder
        // would be saved as-is. The prompt must ask for complete questions only.
        verify(llm).completeJson(any(), argThat(prompt -> prompt.contains("不要生成 CODING") && !prompt.contains("占位")), any());
    }

    @SuppressWarnings("unchecked")
    private List<InterviewQuestion> create(String role, String llmJson) {
        UUID userId = UUID.randomUUID();
        when(profiles.get(userId)).thenReturn(UserProfile.create(userId, "候选人"));
        when(llm.completeJson(any(), any(), any())).thenReturn(new LlmGateway.LlmResult(llmJson, "model"));
        when(hot100.random()).thenReturn(LRU);
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(userId, null, InterviewModality.TEXT, null, role, 3);

        ArgumentCaptor<List<InterviewQuestion>> captor = ArgumentCaptor.forClass(List.class);
        verify(questions).saveAll(captor.capture());
        return captor.getValue();
    }
}
