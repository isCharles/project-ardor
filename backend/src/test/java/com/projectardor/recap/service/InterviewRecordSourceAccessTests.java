package com.projectardor.recap.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.recap.domain.InterviewRecap;
import com.projectardor.recap.domain.InterviewRecapQuestion;
import com.projectardor.recap.repository.InterviewRecapRepository;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;

class InterviewRecordSourceAccessTests {
    private final InterviewRecapRepository records = mock(InterviewRecapRepository.class);
    private final InterviewRecapQuestionRepository questions = mock(InterviewRecapQuestionRepository.class);
    private final InterviewRecordSourceAccess access = new InterviewRecordSourceAccess(records, questions);
    private final UUID owner = UUID.randomUUID(), recordId = UUID.randomUUID(), questionId = UUID.randomUUID();

    private InterviewRecap ownedRecord() {
        var record = mock(InterviewRecap.class);
        when(record.getId()).thenReturn(recordId);
        when(records.findByIdAndUserId(recordId, owner)).thenReturn(Optional.of(record));
        return record;
    }
    private void ownedQuestion() {
        var question = mock(InterviewRecapQuestion.class);
        when(question.getRecapId()).thenReturn(recordId);
        when(questions.findByIdAndUserId(questionId, owner)).thenReturn(Optional.of(question));
    }

    @Test void recordAndQuestionAreExplicitDifferentKindsWithOneParentRecord() {
        var record = ownedRecord(); ownedQuestion();
        UUID interview = UUID.randomUUID(), round = UUID.randomUUID();
        when(record.getJobOpportunityId()).thenReturn(interview);
        when(record.getInterviewRoundId()).thenReturn(round);
        assertThat(access.requireRecord(owner, recordId)).isEqualTo(new InterviewRecordSourceAccess.Source(
                InterviewRecordSourceAccess.Kind.INTERVIEW_RECORD, recordId, recordId, interview, round));
        assertThat(access.requireQuestion(owner, questionId)).isEqualTo(new InterviewRecordSourceAccess.Source(
                InterviewRecordSourceAccess.Kind.INTERVIEW_QUESTION, questionId, recordId, interview, round));
    }

    @Test void legacyInputResolvesOnlyUnambiguousOwnedResources() {
        ownedRecord(); ownedQuestion();
        assertThat(access.requireLegacy(owner, recordId).kind()).isEqualTo(InterviewRecordSourceAccess.Kind.INTERVIEW_RECORD);
        assertThat(access.requireLegacy(owner, questionId).kind()).isEqualTo(InterviewRecordSourceAccess.Kind.INTERVIEW_QUESTION);
    }

    @Test void legacyCollisionRequiresExplicitKindInsteadOfGuessing() {
        var record = ownedRecord();
        when(records.findByIdAndUserId(questionId, owner)).thenReturn(Optional.of(record));
        ownedQuestion();
        assertThatThrownBy(() -> access.requireLegacy(owner, questionId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(access.requireQuestion(owner, questionId).kind()).isEqualTo(InterviewRecordSourceAccess.Kind.INTERVIEW_QUESTION);
    }

    @Test void bothQuestionAndParentRecordMustBeOwned() {
        ownedQuestion();
        assertThatThrownBy(() -> access.requireQuestion(owner, questionId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> access.requireLegacy(owner, questionId)).isInstanceOf(ResourceNotFoundException.class);
        ownedRecord();
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> access.requireRecord(stranger, recordId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> access.requireQuestion(stranger, questionId)).isInstanceOf(ResourceNotFoundException.class);
    }
}
