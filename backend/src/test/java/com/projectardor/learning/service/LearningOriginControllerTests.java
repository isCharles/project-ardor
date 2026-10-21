package com.projectardor.learning.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.web.LearningOriginController;
import com.projectardor.jobsearch.record.service.InterviewRecordSourceAccess;
import com.projectardor.materials.resume.service.ResumeSourceAccess;
import com.projectardor.materials.knowledge.service.KnowledgeSourceAccess;
import com.projectardor.jobsearch.interview.service.InterviewContextAccess;

class LearningOriginControllerTests {
    @Test void sourceNavigationUsesAuthenticatedOwnerAndReturnsOnlyReferenceMetadata() {
        var records = mock(InterviewRecordSourceAccess.class);
        var interviews = mock(InterviewContextAccess.class);
        var resolver = LearningOriginTestHandlers.resolver(records, mock(ResumeSourceAccess.class), mock(KnowledgeSourceAccess.class), interviews);
        var principal = new ArdorPrincipal(UUID.randomUUID(), "fixture@ardor.invalid", "unused", true, UserRole.USER);
        UUID question = UUID.randomUUID(), record = UUID.randomUUID();
        when(records.requireQuestion(principal.userId(), question)).thenReturn(new InterviewRecordSourceAccess.Source(
                InterviewRecordSourceAccess.Kind.INTERVIEW_QUESTION, question, record, UUID.randomUUID(), null));
        var result = new LearningOriginController(resolver).reference(principal, LearningOriginKind.INTERVIEW_QUESTION, question);
        assertThat(result.recordId()).isEqualTo(record);
        assertThat(result.resourceId()).isEqualTo(question);
        verify(records).requireQuestion(principal.userId(), question);
        verifyNoInteractions(interviews);
    }

    @Test void nonResourceOriginsAreRejectedWithoutLookingUpOtherDomains() {
        var resolver = mock(LearningContextResolver.class);
        var principal = new ArdorPrincipal(UUID.randomUUID(), "fixture@ardor.invalid", "unused", true, UserRole.USER);
        for (var kind : new LearningOriginKind[]{LearningOriginKind.MANUAL, LearningOriginKind.AGENT, LearningOriginKind.LEGACY_RECAP}) {
            assertThatThrownBy(() -> new LearningOriginController(resolver).reference(principal, kind, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(resolver);
    }
}
