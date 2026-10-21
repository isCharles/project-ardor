package com.projectardor.learning.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.jobsearch.record.service.InterviewRecordSourceAccess;
import com.projectardor.materials.resume.service.ResumeSourceAccess;
import com.projectardor.materials.knowledge.service.KnowledgeSourceAccess;
import com.projectardor.jobsearch.interview.service.InterviewContextAccess;
import com.projectardor.common.web.ResourceNotFoundException;

class LearningOriginHandlerTests {
    private final InterviewRecordSourceAccess records=mock(InterviewRecordSourceAccess.class);
    private final ResumeSourceAccess resumes=mock(ResumeSourceAccess.class);
    private final KnowledgeSourceAccess knowledge=mock(KnowledgeSourceAccess.class);
    private final InterviewContextAccess interviews=mock(InterviewContextAccess.class);
    private final UUID owner=UUID.randomUUID(),id=UUID.randomUUID();
    private final LearningContextResolver resolver=LearningOriginTestHandlers.resolver(records,resumes,knowledge,interviews);

    @Test void resumeAndKnowledgeValidationStayWithTheirOwnedSourceServices() {
        var resume=resolver.resolve(owner,LearningSourceType.RESUME,id,null);
        var document=resolver.resolve(owner,LearningSourceType.KNOWLEDGE,id,null);
        assertThat(resume.resourceId()).isEqualTo(id);assertThat(document.resourceId()).isEqualTo(id);
        assertThat(resume.recordId()).isNull();assertThat(document.recordId()).isNull();
        verify(resumes).require(owner,id);verify(knowledge).require(owner,id);
        verifyNoInteractions(records,interviews);
    }
    @Test void rejectedSourceNeverReachesPreparationTargetValidation() {
        doThrow(new ResourceNotFoundException("missing")).when(resumes).require(owner,id);
        assertThatThrownBy(() -> resolver.resolve(owner,null,null,new LearningContextInput(LearningOriginKind.RESUME,id,UUID.randomUUID(),null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(interviews,records,knowledge);
    }
    @Test void legacyAutonomousResourceIdsDoNotBecomeCanonicalReferences() {
        for(var type:List.of(LearningSourceType.MANUAL,LearningSourceType.AGENT)) {
            var result=resolver.resolve(owner,type,id,null);
            assertThat(result.resourceId()).isNull();assertThat(result.recordId()).isNull();
        }
        verifyNoInteractions(records,resumes,knowledge,interviews);
    }
    @Test void omittedCanonicalKindCannotSilentlyDiscardAnExplicitResourceId() {
        for(var type:new LearningSourceType[]{null,LearningSourceType.MANUAL,LearningSourceType.AGENT}) {
            assertThatThrownBy(() -> resolver.resolve(owner,type,null,new LearningContextInput(null,id,null,null)))
                    .hasMessageContaining("自主学习来源不接受资源 ID");
        }
        verifyNoInteractions(records,resumes,knowledge,interviews);
    }
    @Test void canonicalRecordAndQuestionKindsCannotBeGuessedFromTheOtherTable() {
        var source=new InterviewRecordSourceAccess.Source(InterviewRecordSourceAccess.Kind.INTERVIEW_RECORD,id,id,null,null);
        when(records.requireRecord(owner,id)).thenReturn(source);
        var resolved=resolver.resolve(owner,null,null,new LearningContextInput(LearningOriginKind.INTERVIEW_RECORD,id,null,null));
        assertThat(resolved.recordId()).isEqualTo(id);
        verify(records).requireRecord(owner,id);verify(records,never()).requireQuestion(any(),any());verify(records,never()).requireLegacy(any(),any());
    }
    @Test void duplicateMissingHistoricalAndEmptyRegistrationsFailImmediately() {
        var handler=mock(LearningOriginHandler.class);when(handler.kinds()).thenReturn(Set.of(LearningOriginKind.RESUME));
        assertThatThrownBy(() -> new LearningContextResolver(List.of(handler,handler),records,interviews)).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new LearningContextResolver(List.of(handler),records,interviews)).hasMessageContaining("Missing");
        when(handler.kinds()).thenReturn(Set.of(LearningOriginKind.LEGACY_RECAP));
        assertThatThrownBy(() -> new LearningContextResolver(List.of(handler),records,interviews)).hasMessageContaining("invalid");
        when(handler.kinds()).thenReturn(Set.of());
        assertThatThrownBy(() -> new LearningContextResolver(List.of(handler),records,interviews)).hasMessageContaining("Empty");
    }
    @Test void coordinatorNoLongerImportsResumeKnowledgeOrDispatchesCanonicalSourcesBySwitch() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/projectardor/learning/service/LearningContextResolver.java"),StandardCharsets.UTF_8);
        assertThat(source).doesNotContain("com.projectardor.materials.resume", "com.projectardor.materials.knowledge", ".repository.", ".web.", "switch (");
    }
}
