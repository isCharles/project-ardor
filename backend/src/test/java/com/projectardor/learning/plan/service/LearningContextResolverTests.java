package com.projectardor.learning.plan.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.learning.plan.domain.LearningOriginKind;
import com.projectardor.learning.plan.domain.LearningSourceType;
import com.projectardor.jobsearch.record.service.InterviewRecordSourceAccess;
import com.projectardor.materials.resume.service.ResumeSourceAccess;
import com.projectardor.materials.knowledge.service.KnowledgeSourceAccess;
import com.projectardor.jobsearch.interview.service.InterviewContextAccess;
import com.projectardor.jobsearch.interview.repository.JobOpportunityRepository;
import com.projectardor.jobsearch.interview.repository.OpportunityRoundRepository;

class LearningContextResolverTests {
    private final InterviewRecordSourceAccess sources = mock(InterviewRecordSourceAccess.class);
    private final InterviewContextAccess interviews = mock(InterviewContextAccess.class);
    private final LearningContextResolver resolver = LearningOriginTestHandlers.resolver(sources, mock(ResumeSourceAccess.class), mock(KnowledgeSourceAccess.class), interviews);
    private final UUID owner=UUID.randomUUID(), question=UUID.randomUUID(), record=UUID.randomUUID();

    private InterviewRecordSourceAccess.Source source() {
        return new InterviewRecordSourceAccess.Source(InterviewRecordSourceAccess.Kind.INTERVIEW_QUESTION,
                question,record,UUID.randomUUID(),UUID.randomUUID());
    }

    @Test void legacyQuestionBecomesExplicitButItsSourceInterviewDoesNotBecomeTheTarget() {
        var source=source();
        when(sources.requireLegacy(owner,question)).thenReturn(source);
        when(sources.requireQuestion(owner,question)).thenReturn(source);
        var resolved=resolver.resolve(owner,LearningSourceType.RECAP,question,null);
        assertThat(resolved.kind()).isEqualTo(LearningOriginKind.INTERVIEW_QUESTION);
        assertThat(resolved.resourceId()).isEqualTo(question);
        assertThat(resolved.recordId()).isEqualTo(record);
        assertThat(resolved.targetInterviewId()).isNull();
        assertThat(resolved.targetRoundId()).isNull();
        verifyNoInteractions(interviews);
    }

    @Test void explicitSourceCanPrepareForADifferentOwnedInterviewAndRound() {
        var source=source();
        when(sources.requireQuestion(owner,question)).thenReturn(source);
        UUID target=UUID.randomUUID(),round=UUID.randomUUID();
        var resolved=resolver.resolve(owner,null,null,new LearningContextInput(LearningOriginKind.INTERVIEW_QUESTION,question,target,round));
        assertThat(resolved.targetInterviewId()).isEqualTo(target);
        assertThat(resolved.targetRoundId()).isEqualTo(round);
        verify(interviews).require(owner,target,round);
        verify(sources,never()).requireLegacy(any(),any());
    }

    @Test void conflictingLegacyAndCanonicalReferencesAreRejectedBeforeReadingSources() {
        assertThatThrownBy(() -> resolver.resolve(owner,LearningSourceType.RESUME,question,
                new LearningContextInput(LearningOriginKind.INTERVIEW_QUESTION,question,null,null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(owner,LearningSourceType.RECAP,record,
                new LearningContextInput(LearningOriginKind.INTERVIEW_QUESTION,question,null,null))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sources,interviews);
    }

    @Test void historicalUncertaintyCannotBeRequestedAsANewSource() {
        assertThatThrownBy(() -> resolver.resolve(owner,null,null,
                new LearningContextInput(LearningOriginKind.LEGACY_RECAP,question,null,null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void autonomousLearningNeedsNeitherInterviewNorSourceResource() {
        var resolved=resolver.resolve(owner,LearningSourceType.MANUAL,null,null);
        assertThat(resolved.kind()).isEqualTo(LearningOriginKind.MANUAL);
        assertThat(resolved.resourceId()).isNull();
        assertThat(resolved.targetInterviewId()).isNull();
        verifyNoInteractions(sources,interviews);
    }

    @Test void targetRoundCannotBeSuppliedWithoutItsParentInterview() {
        var realAccess=new InterviewContextAccess(mock(JobOpportunityRepository.class),mock(OpportunityRoundRepository.class));
        var resolverWithParentChecks=LearningOriginTestHandlers.resolver(sources,mock(ResumeSourceAccess.class),mock(KnowledgeSourceAccess.class),realAccess);
        assertThatThrownBy(() -> resolverWithParentChecks.resolve(owner,LearningSourceType.MANUAL,null,
                new LearningContextInput(null,null,null,UUID.randomUUID()))).isInstanceOf(IllegalArgumentException.class);
    }
}
