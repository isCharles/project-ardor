package com.projectardor.learning.practice.mockinterview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.learning.practice.mockinterview.domain.*;
import com.projectardor.learning.practice.mockinterview.repository.*;
import com.projectardor.learning.practice.mockinterview.web.*;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.platform.identity.profile.domain.UserProfile;
import com.projectardor.platform.model.llm.service.LlmGateway;
import com.projectardor.materials.resume.service.ResumeService;
import com.projectardor.jobsearch.interview.service.OpportunityService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

class MockInterviewApplicationContractTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID owner = UUID.randomUUID(), process = UUID.randomUUID();
    private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final InterviewAnswerRepository answers = mock(InterviewAnswerRepository.class);
    private final InterviewEvaluationRepository evaluations = mock(InterviewEvaluationRepository.class);
    private final LlmGateway llm = mock(LlmGateway.class);
    private final OpportunityService opportunities = mock(OpportunityService.class);
    private final InterviewService service = new InterviewService(sessions, questions, answers, evaluations,
            mock(ProfileService.class), mock(ResumeService.class), llm, new LlmJsonParser(mapper), mapper,
            mock(PlatformTransactionManager.class), mock(LeetCodeHot100.class), opportunities);

    private InterviewSession session() {
        return InterviewSession.create(owner, null, InterviewModality.TEXT, null, "Backend", process);
    }

    @Test void sessionSnapshotKeepsExactlyTheLegacyJsonFieldsAndNulls() {
        var entity = session();
        var view = MockInterviewSessionView.from(entity);
        var json = mapper.valueToTree(view);
        assertThat(json.propertyNames()).containsExactly("id", "resumeAnalysisId", "jobOpportunityId", "modality",
                "targetCompany", "targetRole", "status", "startedAt", "finishedAt", "createdAt");
        assertThat(json.path("resumeAnalysisId").isNull()).isTrue();
        assertThat(json.path("targetCompany").isNull()).isTrue();
        assertThat(json.path("jobOpportunityId").asText()).isEqualTo(process.toString());
        assertThat((JsonNode) mapper.valueToTree(InterviewSessionResponse.fromView(view))).isEqualTo(json);
        assertThat((JsonNode) mapper.valueToTree(InterviewSessionResponse.from(entity))).isEqualTo(json);
        entity.cancel();
        assertThat(view.status()).isEqualTo(InterviewStatus.IN_PROGRESS);
        assertThat(view.finishedAt()).isNull();
    }

    @Test void nextQuestionAndCodingCatalogKeepTheLegacyWireShape() {
        var entity = session();
        var question = InterviewQuestion.create(owner, entity.getId(), 2, "Synthetic coding question", "CODING", List.of("Correctness"), "two-sum");
        var view = MockInterviewProgressView.from(new InterviewService.InterviewProgress(entity, question, 1, 3));
        var json = mapper.valueToTree(view);
        assertThat(json.propertyNames()).containsExactly("sessionId", "status", "answeredCount", "totalQuestions", "readyToFinish", "nextQuestion");
        assertThat(json.path("nextQuestion").propertyNames()).containsExactly("id", "sequenceNumber", "questionText", "questionType", "evaluationCriteria", "leetcode");
        assertThat(json.path("nextQuestion").path("leetcode").propertyNames()).containsExactly("id", "slug", "titleEn", "titleZh");
        assertThat(json.path("nextQuestion").path("leetcode").path("id").asInt()).isEqualTo(1);
        assertThat((JsonNode) mapper.valueToTree(InterviewProgressResponse.fromView(view))).isEqualTo(json);
        assertThat(view.readyToFinish()).isFalse();
        var unknown = InterviewQuestion.create(owner, entity.getId(), 1, "Unknown", "CODING", List.of(), "not-in-catalog");
        assertThat(MockInterviewProgressView.Question.from(unknown).leetcode()).isNull();
        assertThat(InterviewQuestionResponse.from(null)).isNull();
    }

    @Test void readinessRequiresAnActiveSessionNoNextQuestionAndPositiveQuestionCount() {
        var entity = session();
        assertThat(MockInterviewProgressView.from(new InterviewService.InterviewProgress(entity, null, 0, 0)).readyToFinish()).isFalse();
        var ready = MockInterviewProgressView.from(new InterviewService.InterviewProgress(entity, null, 3, 3));
        assertThat(ready.readyToFinish()).isTrue();
        assertThat(ready.nextQuestion()).isNull();
        entity.complete();
        assertThat(MockInterviewProgressView.from(new InterviewService.InterviewProgress(entity, null, 3, 3)).readyToFinish()).isFalse();
        var cancelled = session(); cancelled.cancel();
        assertThat(MockInterviewProgressView.from(new InterviewService.InterviewProgress(cancelled, null, 3, 3)).readyToFinish()).isFalse();
    }

    @Test void criteriaCannotBeMutatedThroughTheCallerOrTheReturnedView() {
        var criteria = new ArrayList<String>(Arrays.asList("Correctness", null));
        var question = new MockInterviewProgressView.Question(UUID.randomUUID(), 1, "Question", "TECHNICAL", criteria, null);
        criteria.clear();
        assertThat(question.evaluationCriteria()).containsExactly("Correctness", null);
        assertThatThrownBy(() -> question.evaluationCriteria().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void evaluationSnapshotIsRecursiveAndRetainsNullsAndTheSevenLegacyFields() {
        var nested = new LinkedHashMap<String, Object>(); nested.put("observation", null);
        var rows = new ArrayList<Object>(); rows.add(nested); rows.add(null);
        var input = new LinkedHashMap<String, Object>(); input.put("strengths", rows); input.put("optional", null);
        var view = new MockInterviewEvaluationView(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("86.00"), input,
                "fixture", "interview-evaluation-v1", Instant.parse("2026-10-01T00:00:00Z"));
        var expected = mapper.valueToTree(view);
        nested.put("observation", "changed"); rows.clear(); input.clear();
        assertThat((JsonNode) mapper.valueToTree(view)).isEqualTo(expected);
        assertThat(expected.propertyNames()).containsExactly("id", "interviewSessionId", "overallScore", "evaluation", "modelName", "promptVersion", "createdAt");
        assertThat((JsonNode) mapper.valueToTree(InterviewEvaluationResponse.fromView(view))).isEqualTo(expected);
        assertThatThrownBy(() -> view.evaluation().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) view.evaluation().get("strengths")).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) ((List<?>) view.evaluation().get("strengths")).getFirst()).clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void readViewsStillBindEveryLookupToTheCurrentOwner() {
        var entity = session();
        when(sessions.findByIdAndUserId(entity.getId(), owner)).thenReturn(Optional.of(entity));
        when(sessions.findAllByUserIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(entity));
        assertThat(service.getView(owner, entity.getId()).id()).isEqualTo(entity.getId());
        assertThat(service.listViews(owner)).hasSize(1);
        assertThatThrownBy(() -> service.getView(UUID.randomUUID(), entity.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.evaluationView(UUID.randomUUID(), entity.getId())).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(evaluations, llm);
    }

    @Test void cancellationFlushesTheOwnedMutationBeforeReturningASnapshot() {
        var entity = session();
        when(sessions.findOwnedForUpdate(entity.getId(), owner)).thenReturn(Optional.of(entity));
        when(sessions.save(entity)).thenReturn(entity);
        var view = service.cancelView(owner, entity.getId());
        assertThat(view.status()).isEqualTo(InterviewStatus.CANCELLED);
        assertThat(view.finishedAt()).isNotNull();
        var order = inOrder(sessions);
        order.verify(sessions).findOwnedForUpdate(entity.getId(), owner);
        order.verify(sessions).save(entity);
        order.verify(sessions).flush();
        assertThatThrownBy(() -> service.cancelView(UUID.randomUUID(), entity.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void answerViewUsesTheExistingOrderingAndReturnsThePostSaveProgress() {
        var entity = session();
        var question = InterviewQuestion.create(owner, entity.getId(), 1, "Question", "TECHNICAL", List.of());
        var answer = InterviewAnswer.create(owner, entity.getId(), question.getId(), "Answer", 12);
        when(sessions.findByIdAndUserId(entity.getId(), owner)).thenReturn(Optional.of(entity));
        when(sessions.findOwnedForUpdate(entity.getId(), owner)).thenReturn(Optional.of(entity));
        when(questions.findByIdAndUserIdAndInterviewSessionId(question.getId(), owner, entity.getId())).thenReturn(Optional.of(question));
        when(questions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(owner, entity.getId())).thenReturn(List.of(question));
        when(answers.findAllByUserIdAndInterviewSessionId(owner, entity.getId())).thenReturn(List.of(), List.of(answer));
        var result = service.submitAnswerView(owner, entity.getId(), question.getId(), "Answer", 12);
        assertThat(result.answeredCount()).isEqualTo(1);
        assertThat(result.readyToFinish()).isTrue();
        verify(answers).save(any(InterviewAnswer.class));
        verify(sessions).flush();
    }

    @Test void httpUsesTheSameOwnedResultsAndAuthenticatedOwner() {
        var app = mock(InterviewService.class);
        var principal = new com.projectardor.platform.identity.auth.security.ArdorPrincipal(owner, "fixture@ardor.invalid", "unused", true,
                com.projectardor.platform.identity.auth.domain.UserRole.USER);
        var view = MockInterviewSessionView.from(session());
        when(app.createView(owner, null, InterviewModality.TEXT, null, "Backend", 5, process)).thenReturn(view);
        var controller = new InterviewController(app, mock(com.projectardor.platform.jobs.aijobs.AiJobService.class));
        var result = controller.create(principal, new CreateInterviewRequest(null, null, null, "Backend", null, process));
        assertThat((JsonNode) mapper.valueToTree(result)).isEqualTo(mapper.valueToTree(view));
        verify(app).createView(owner, null, InterviewModality.TEXT, null, "Backend", 5, process);
    }

    @Test void creationCallsTheModelOutsideATransactionAndSavesInsideOne() {
        var profiles = mock(ProfileService.class);
        var materials = mock(ResumeService.class);
        UUID analysisId = UUID.randomUUID();
        var context = new com.projectardor.materials.resume.service.ResumeAnalysisSnapshot(
                analysisId, UUID.randomUUID(), Map.of("summary", "Synthetic preparation context"),
                "fixture", "fixture-v1", null);
        when(materials.analysisContext(owner, analysisId)).thenReturn(context);
        var profile = mock(UserProfile.class);
        when(profile.getTargetRoles()).thenReturn(List.of("Designer"));
        when(profiles.get(owner)).thenReturn(profile);
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(call.<String>getArgument(2)).contains("Synthetic preparation context");
            return new LlmGateway.LlmResult("""
                {"questions":[{"questionText":"One"},{"questionText":"Two"},{"questionText":"Three"}]}
                """, "fixture");
        });
        when(sessions.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return call.getArgument(0);
        });
        var app = new InterviewService(sessions, questions, answers, evaluations, profiles, materials,
                llm, new LlmJsonParser(mapper), mapper, new LocalTransactionManager(), new LeetCodeHot100(), opportunities);
        var created = app.createView(owner, analysisId, InterviewModality.TEXT, null, "Designer", 3, null);
        assertThat(created.status()).isEqualTo(InterviewStatus.IN_PROGRESS);
        assertThat(created.resumeAnalysisId()).isEqualTo(analysisId);
        verify(materials).analysisContext(owner, analysisId);
        verify(materials, never()).getAnalysisById(any(), any());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void evaluationCallsTheModelOutsideATransactionAndCommitsBeforeSnapshot() {
        var entity = session();
        var question = InterviewQuestion.create(owner, entity.getId(), 1, "Question", "TECHNICAL", List.of());
        when(sessions.findByIdAndUserId(entity.getId(), owner)).thenReturn(Optional.of(entity));
        when(sessions.findOwnedForUpdate(entity.getId(), owner)).thenReturn(Optional.of(entity));
        when(questions.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(owner, entity.getId())).thenReturn(List.of(question));
        when(answers.findAllByUserIdAndInterviewSessionId(owner, entity.getId())).thenReturn(List.of(
                InterviewAnswer.create(owner, entity.getId(), question.getId(), "Answer", null)));
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new LlmGateway.LlmResult("{\"overallScore\":86}", "fixture");
        });
        when(evaluations.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return call.getArgument(0);
        });
        var app = new InterviewService(sessions, questions, answers, evaluations, mock(ProfileService.class), mock(ResumeService.class),
                llm, new LlmJsonParser(mapper), mapper, new LocalTransactionManager(), new LeetCodeHot100(), opportunities);
        assertThat(app.finishView(owner, entity.getId()).overallScore()).isEqualByComparingTo("86.00");
        assertThat(entity.getStatus()).isEqualTo(InterviewStatus.COMPLETED);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private static class LocalTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }

    @Test void foreignResumeContextFailsBeforeModelOrSessionWrite() {
        var materials = mock(ResumeService.class);
        var profiles = mock(ProfileService.class);
        when(profiles.get(owner)).thenReturn(mock(UserProfile.class));
        UUID analysisId = UUID.randomUUID();
        when(materials.analysisContext(owner, analysisId)).thenThrow(new ResourceNotFoundException("Not found"));
        var app = new InterviewService(sessions, questions, answers, evaluations, profiles, materials,
                llm, new LlmJsonParser(mapper), mapper, new LocalTransactionManager(), new LeetCodeHot100(), opportunities);
        assertThatThrownBy(() -> app.createView(owner, analysisId, InterviewModality.TEXT, null, "Designer", 3, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(llm, sessions, questions);
    }

    @Test void foreignRealInterviewFailsBeforeAnyModelOrProfileLookup() {
        var foreign = UUID.randomUUID();
        when(opportunities.detail(owner, foreign)).thenThrow(new ResourceNotFoundException("Not found"));
        assertThatThrownBy(() -> service.createView(owner, null, InterviewModality.TEXT, "Fixture", "Backend", 3, foreign))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(llm, sessions, questions);
    }

    @Test void modelCallingViewEntrypointsMustNotStartOuterTransactions() throws Exception {
        assertThat(InterviewService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(InterviewService.class.getMethod("createView", UUID.class, UUID.class, InterviewModality.class,
                String.class, String.class, int.class, UUID.class).getAnnotation(Transactional.class)).isNull();
        assertThat(InterviewService.class.getMethod("finishView", UUID.class, UUID.class).getAnnotation(Transactional.class)).isNull();
        assertThat(InterviewService.class.getMethod("getView", UUID.class, UUID.class).getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(InterviewService.class.getMethod("cancelView", UUID.class, UUID.class).getAnnotation(Transactional.class).readOnly()).isFalse();
    }
}
