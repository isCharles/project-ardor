package com.projectardor.learning.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.learning.domain.LearningStatus;
import com.projectardor.learning.repository.LearningPlanRepository;
import com.projectardor.learning.repository.LearningAttemptRepository;
import com.projectardor.recap.service.InterviewQuestionProgressAccess;
import com.projectardor.recap.service.QuestionPracticeProgressAccess;
import com.projectardor.recap.service.InterviewRecordSourceAccess;
import com.projectardor.opportunity.service.InterviewContextAccess;
import com.projectardor.common.web.ResourceNotFoundException;

class LearningProgressServiceTests {
    private final InterviewQuestionProgressAccess questions=mock(InterviewQuestionProgressAccess.class);
    private final QuestionPracticeProgressAccess practice=mock(QuestionPracticeProgressAccess.class);
    private final LearningPlanRepository plans=mock(LearningPlanRepository.class);
    private final LearningAttemptRepository attempts=mock(LearningAttemptRepository.class);
    private final InterviewRecordSourceAccess sources=mock(InterviewRecordSourceAccess.class);
    private final InterviewContextAccess interviews=mock(InterviewContextAccess.class);
    private final LearningProgressService service=new LearningProgressService(questions,practice,plans,attempts,sources,interviews);
    private final UUID owner=UUID.randomUUID(),question=UUID.randomUUID(),record=UUID.randomUUID(),sourceInterview=UUID.randomUUID(),round=UUID.randomUUID();

    private void oneQuestion() {
        when(questions.questions(owner,null,0,20)).thenReturn(new InterviewQuestionProgressAccess.Window(1,List.of(
                new InterviewQuestionProgressAccess.Question(question,record,"Fixture question","Fixture observation","WEAK","Fixture record",Instant.now(),sourceInterview,round))));
        when(practice.snapshot(any(),any())).thenReturn(new QuestionPracticeProgressAccess.Snapshot(3,4,Map.of(),Map.of()));
    }
    private LearningPlanRepository.ProgressPlan plan(LearningOriginKind kind,UUID id) {
        var plan=mock(LearningPlanRepository.ProgressPlan.class);
        when(plan.getId()).thenReturn(UUID.randomUUID());when(plan.getConcept()).thenReturn("Fixture concept");
        when(plan.getStatus()).thenReturn(LearningStatus.SCHEDULED);when(plan.getSourceType()).thenReturn(LearningSourceType.RECAP);
        when(plan.getSourceId()).thenReturn(id);when(plan.getOriginKind()).thenReturn(kind);when(plan.getOriginResourceId()).thenReturn(id);
        return plan;
    }
    @Test void preservesAllExactAndInterviewWidePlansWithoutTreatingTargetsAsSources() {
        oneQuestion();var first=plan(LearningOriginKind.INTERVIEW_QUESTION,question);var second=plan(LearningOriginKind.INTERVIEW_QUESTION,question);
        var broad=plan(LearningOriginKind.INTERVIEW_RECORD,record);UUID target=UUID.randomUUID();when(first.getTargetInterviewId()).thenReturn(target);
        when(plans.progressPlans(eq(owner),any())).thenReturn(List.of(first,second,broad));
        var track=service.view(owner,null,0,20).tracks().getFirst();
        assertThat(track.learningPlans()).hasSize(2);assertThat(track.interviewLearningPlans()).hasSize(1);
        assertThat(track.question().interviewId()).isEqualTo(sourceInterview);assertThat(track.question().roundId()).isEqualTo(round);
        assertThat(track.learningPlans().getFirst().targetInterviewId()).isEqualTo(target);
        verifyNoInteractions(sources);
    }
    @Test void ambiguousLegacySourcesStayUnlinkedInsteadOfGuessingFromPageIds() {
        oneQuestion();var legacy=plan(null,question);when(plans.progressPlans(eq(owner),any())).thenReturn(List.of(legacy));
        when(sources.requireLegacy(owner,question)).thenThrow(new IllegalArgumentException("ambiguous"));
        var result=service.view(owner,null,0,20);
        assertThat(result.unresolvedSources()).isEqualTo(1);assertThat(result.tracks().getFirst().learningPlans()).isEmpty();
    }
    @Test void noInterviewQuestionsDoesNotHideAutonomousLearningAndReviewCounts() {
        when(questions.questions(owner,null,0,20)).thenReturn(new InterviewQuestionProgressAccess.Window(0,List.of()));
        when(practice.snapshot(owner,java.util.Set.of())).thenReturn(new QuestionPracticeProgressAccess.Snapshot(2,0,Map.of(),Map.of()));
        when(plans.countByUserId(owner)).thenReturn(5L);when(attempts.countByUserId(owner)).thenReturn(7L);
        var result=service.view(owner,null,0,20);
        assertThat(result.tracks()).isEmpty();assertThat(result.accountSummary().learningPlans()).isEqualTo(5);
        assertThat(result.accountSummary().recordedLearningAttempts()).isEqualTo(7);assertThat(result.accountSummary().flashcards()).isEqualTo(2);
        verify(plans,never()).progressPlans(any(),any());
    }
    @Test void foreignSourceInterviewIsRejectedBeforeAnyProgressLookup() {
        doThrow(new ResourceNotFoundException("missing")).when(interviews).require(owner,sourceInterview,null);
        assertThatThrownBy(() -> service.view(owner,sourceInterview,0,20)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(questions,practice,plans,attempts,sources);
    }
    @Test void invalidPaginationNeverReachesRepositories() {
        for(int[] input:new int[][]{{-1,20},{0,0},{0,51},{Integer.MAX_VALUE,20}})
            assertThatThrownBy(() -> service.view(owner,null,input[0],input[1])).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(questions,practice,plans,attempts,sources,interviews);
    }
    @Test void learningQueryCannotReachOtherDomainsRepositoriesOrTransportDtos() throws Exception {
        String code=Files.readString(Path.of("src/main/java/com/projectardor/learning/service/LearningProgressService.java"),StandardCharsets.UTF_8);
        assertThat(code).doesNotContain("com.projectardor.recap.repository", "com.projectardor.opportunity.repository", "com.projectardor.learning.web");
    }
}
