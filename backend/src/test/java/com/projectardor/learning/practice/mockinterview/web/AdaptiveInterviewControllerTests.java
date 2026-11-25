package com.projectardor.learning.practice.mockinterview.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.projectardor.common.web.ApiExceptionHandler;
import com.projectardor.config.SecurityConfig;
import com.projectardor.learning.practice.mockinterview.domain.DifficultyFeedback;
import com.projectardor.learning.practice.mockinterview.domain.InterviewEvaluation;
import com.projectardor.learning.practice.mockinterview.domain.InterviewModality;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;
import com.projectardor.learning.practice.mockinterview.domain.PolicyAction;
import com.projectardor.learning.practice.mockinterview.domain.QuestionOrigin;
import com.projectardor.learning.practice.mockinterview.service.InterviewService;
import com.projectardor.learning.practice.mockinterview.service.MockInterviewSessionView;
import com.projectardor.learning.practice.mockinterview.service.adaptive.AdaptiveInterviewOrchestrator;
import com.projectardor.learning.practice.mockinterview.service.adaptive.AdaptiveInterviewState;
import com.projectardor.platform.identity.auth.domain.UserAccount;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.repository.UserAccountRepository;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.jobs.aijobs.AiJobService;

/**
 * Both interview controllers are registered together on purpose: the adaptive routes add a literal
 * segment where the classic controller expects a session UUID, so a precedence mistake would send
 * adaptive traffic into the classic service instead of failing loudly.
 */
class AdaptiveInterviewControllerTests {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    AdaptiveInterviewOrchestrator orchestrator;
    InterviewService classics;
    final UUID owner = UUID.randomUUID();
    final UUID sessionId = UUID.randomUUID();

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        orchestrator = context.getBean(AdaptiveInterviewOrchestrator.class);
        classics = context.getBean(InterviewService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach void close() { context.close(); }

    ArdorPrincipal principal() { return new ArdorPrincipal(owner, "synthetic@ardor.invalid", "", true, UserRole.USER); }

    private AdaptiveInterviewState state() {
        return new AdaptiveInterviewState(sessionId, InterviewStatus.IN_PROGRESS, "后端工程师", "Acme", "MID",
                30, false, Instant.now(), 1, 0, false, false,
                new AdaptiveInterviewState.Question(UUID.randomUUID(), 1, "联合索引什么时候失效？", "TECHNICAL",
                        "数据库", "索引失效", 3, QuestionOrigin.PLAN, null),
                null, List.of(), new AdaptiveInterviewState.Decision(PolicyAction.OPEN, "开始考察数据库", Instant.now()));
    }

    @Test void requiresAuthenticationAndCsrfForMutations() throws Exception {
        mvc.perform(get("/api/interviews/adaptive/" + sessionId)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/interviews/adaptive").with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"targetRole\":\"后端工程师\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orchestrator, classics);
    }

    @Test void theAdaptiveCollectionIsNotMistakenForASessionId() throws Exception {
        when(orchestrator.list(owner)).thenReturn(List.of(MockInterviewSessionView.from(
                InterviewSession.createAdaptive(owner, null, "Acme", "后端工程师", null, "MID", 30, false, null))));
        mvc.perform(get("/api/interviews/adaptive").with(user(principal())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].targetRole").value("后端工程师"));
        verify(orchestrator).list(owner);
        verifyNoInteractions(classics);
    }

    @Test void theAdaptiveStateRouteReachesTheOrchestratorNotTheClassicService() throws Exception {
        when(orchestrator.state(owner, sessionId)).thenReturn(state());
        mvc.perform(get("/api/interviews/adaptive/" + sessionId).with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.currentQuestion.knowledgePoint").value("索引失效"))
                .andExpect(jsonPath("$.currentQuestion.rubric").doesNotExist());
        verify(orchestrator).state(owner, sessionId);
        verifyNoInteractions(classics);
    }

    @Test void createTakesTheOwnerFromThePrincipalAndForwardsTheAdaptiveSettings() throws Exception {
        when(orchestrator.start(eq(owner), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(InterviewSession.createAdaptive(owner, null, "Acme", "后端工程师", null, "MID", 45, true, "JD"));
        mvc.perform(post("/api/interviews/adaptive").with(user(principal())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"userId":"%s","targetRole":"后端工程师","targetCompany":"Acme","seniority":"MID",
                         "durationMinutes":45,"deepDive":true,"jobDescription":"JD"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetRole").value("后端工程师"));
        verify(orchestrator).start(owner, null, "Acme", "后端工程师", null, "MID", 45, true, "JD");
    }

    @Test void createRejectsABlankRoleOrANonPositiveDurationBeforeCallingTheModel() throws Exception {
        mvc.perform(post("/api/interviews/adaptive").with(user(principal())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"targetRole\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/interviews/adaptive").with(user(principal())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetRole\":\"后端工程师\",\"durationMinutes\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orchestrator);
    }

    @Test void submitAnswerForwardsTheDifficultyFeedbackWithThePrincipalOwner() throws Exception {
        UUID questionId = UUID.randomUUID();
        when(orchestrator.submitAnswer(eq(owner), eq(sessionId), eq(questionId), anyString(), any(), any()))
                .thenReturn(state());
        mvc.perform(post("/api/interviews/adaptive/" + sessionId + "/answers")
                .with(user(principal())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"questionId":"%s","answerText":"最左前缀不满足时失效","durationSeconds":40,
                         "difficultyFeedback":"TOO_HARD"}
                        """.formatted(questionId)))
                .andExpect(status().isOk());
        verify(orchestrator).submitAnswer(owner, sessionId, questionId, "最左前缀不满足时失效", 40,
                DifficultyFeedback.TOO_HARD);
    }

    @Test void finishReturnsTheSavedReport() throws Exception {
        when(orchestrator.finish(owner, sessionId)).thenReturn(InterviewEvaluation.create(owner, sessionId,
                new BigDecimal("3.20"), Map.of("reportKind", "ADAPTIVE"), "test-model", "adaptive-report-v1"));
        mvc.perform(post("/api/interviews/adaptive/" + sessionId + "/finish").with(user(principal())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(3.20))
                .andExpect(jsonPath("$.evaluation.reportKind").value("ADAPTIVE"));
        verify(orchestrator).finish(owner, sessionId);
    }

    @Test void theClassicSessionRouteStillReachesTheClassicService() throws Exception {
        InterviewSession classic = InterviewSession.create(owner, null, InterviewModality.TEXT, null, "后端工程师");
        org.springframework.test.util.ReflectionTestUtils.setField(classic, "id", sessionId);
        when(classics.getView(owner, sessionId)).thenReturn(MockInterviewSessionView.from(classic));
        mvc.perform(get("/api/interviews/" + sessionId).with(user(principal())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(sessionId.toString()));
        verify(classics).getView(owner, sessionId);
        verifyNoInteractions(orchestrator);
    }

    @Configuration @EnableWebMvc @EnableWebSecurity @Import(SecurityConfig.class)
    static class Config {
        @Bean AdaptiveInterviewOrchestrator orchestrator() { return mock(AdaptiveInterviewOrchestrator.class); }
        @Bean InterviewService classics() { return mock(InterviewService.class); }
        @Bean AiJobService jobs() { return mock(AiJobService.class); }
        @Bean AdaptiveInterviewController adaptive(AdaptiveInterviewOrchestrator orchestrator) {
            return new AdaptiveInterviewController(orchestrator);
        }
        @Bean InterviewController classic(InterviewService classics, AiJobService jobs) {
            return new InterviewController(classics, jobs);
        }
        @Bean ApiExceptionHandler errors() { return new ApiExceptionHandler(); }
        @Bean UserAccountRepository users() {
            var users = mock(UserAccountRepository.class);
            when(users.findById(any())).thenReturn(Optional.of(UserAccount.create("fixture@ardor.invalid", "")));
            return users;
        }
    }
}