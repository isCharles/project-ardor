package com.projectardor.recap.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.recap.domain.InterviewRecapSource;
import com.projectardor.recap.domain.QuestionPerformance;
import com.projectardor.recap.domain.ReplayVerdict;
import com.projectardor.recap.web.InterviewRecapController;
import com.projectardor.recap.web.InterviewRecapResponse;
import com.projectardor.recap.web.InterviewReplayController;
import com.projectardor.recap.web.InterviewReplayResponse;
import com.projectardor.aijobs.AiJobService;
import tools.jackson.databind.json.JsonMapper;

class InterviewRecordApplicationContractTests {
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final UUID record=UUID.randomUUID(),question=UUID.randomUUID(),process=UUID.randomUUID(),round=UUID.randomUUID();
    private final Instant time=Instant.parse("2026-10-20T01:00:00Z");
    private InterviewRecordView recordView() {
        return new InterviewRecordView(record,"Fixture record",InterviewRecapSource.NOTES,"Overview",List.of("Observed"),
                List.of(),"fixture-model",time,process,List.of(new InterviewRecordView.Question(question,2,
                    "原始问题",null,List.of("追问"),"",QuestionPerformance.UNKNOWN,null,"",List.of("topic"))),round);
    }
    private QuestionPracticeView practiceView() {
        return new QuestionPracticeView(question,record,"Fixture record","原始问题",null,"",null,List.of(
                new QuestionPracticeView.Attempt(UUID.randomUUID(),UUID.randomUUID(),"Synthetic answer",ReplayVerdict.UNKNOWN,
                    "Insufficient original evidence",List.of(),List.of("Gap"),"",null,time)));
    }
    @Test void recordApplicationAndLegacyHttpShapesStayByteForByteCompatible() {
        var view=recordView();var q=view.questions().getFirst();
        // Construct the old public record explicitly, independently of the new mapper.
        var expected=new InterviewRecapResponse(record,"Fixture record",InterviewRecapSource.NOTES,"Overview",List.of("Observed"),
                List.of(),"fixture-model",time,process,List.of(new InterviewRecapResponse.QuestionResponse(question,2,
                    "原始问题",null,List.of("追问"),"",QuestionPerformance.UNKNOWN,null,"",List.of("topic"))),round);
        assertThat(mapper.writeValueAsString(view)).isEqualTo(mapper.writeValueAsString(expected));
        assertThat(mapper.writeValueAsString(InterviewRecapResponse.from(view))).isEqualTo(mapper.writeValueAsString(expected));
        var json=mapper.readTree(mapper.writeValueAsString(view));
        assertThat(json.propertyNames()).containsExactly("id","title","sourceType","overview","strengths","weaknesses","modelName","createdAt","jobOpportunityId","questions","interviewRoundId");
        assertThat(json.path("questions").get(0).propertyNames()).containsExactly("id","sequenceNumber","questionText","candidateAnswer","followUps","assessment","performance","weaknessReason","betterAnswer","tags");
        assertThat(q.candidateAnswer()).isNull();assertThat(q.assessment()).isEmpty();
    }
    @Test void practiceApplicationAndLegacyHttpShapesKeepAllHistoricalAttemptFields() {
        var view=practiceView();var a=view.attempts().getFirst();
        var expected=new InterviewReplayResponse(question,record,"Fixture record","原始问题",null,"",null,List.of(
                new InterviewReplayResponse.Attempt(a.id(),a.requestId(),"Synthetic answer",ReplayVerdict.UNKNOWN,
                    "Insufficient original evidence",List.of(),List.of("Gap"),"",null,time)));
        assertThat(mapper.writeValueAsString(view)).isEqualTo(mapper.writeValueAsString(expected));
        assertThat(mapper.writeValueAsString(InterviewReplayResponse.from(view))).isEqualTo(mapper.writeValueAsString(expected));
        assertThat(mapper.readTree(mapper.writeValueAsString(view)).path("attempts").get(0).propertyNames())
                .containsExactly("id","requestId","answerText","verdict","comparison","improvements","remainingGaps","nextChallenge","challengeText","createdAt");
    }
    @Test void snapshotsDoNotAliasMutableListsAndKeepLegacyNullSemantics() {
        var values=new ArrayList<>(Arrays.asList("Original",null));
        var q=new InterviewRecordView.Question(question,1,"Question",null,values,null,QuestionPerformance.UNKNOWN,null,null,values);
        var view=new InterviewRecordView(record,"Record",InterviewRecapSource.NOTES,null,values,null,null,null,null,new ArrayList<>(List.of(q)),null);
        var attempt=new QuestionPracticeView.Attempt(UUID.randomUUID(),UUID.randomUUID(),"Answer",ReplayVerdict.UNKNOWN,null,values,null,null,null,null);
        var attempts=new ArrayList<>(List.of(attempt));
        var practice=new QuestionPracticeView(question,record,null,null,null,null,null,attempts);
        values.clear();attempts.clear();
        assertThat(view.strengths()).containsExactly("Original",null);assertThat(view.weaknesses()).isNull();
        assertThat(q.followUps()).containsExactly("Original",null);assertThat(q.tags()).containsExactly("Original",null);
        assertThat(practice.attempts()).hasSize(1);assertThat(attempt.improvements()).containsExactly("Original",null);
        assertThat(attempt.remainingGaps()).isNull();
        assertThatThrownBy(()->view.questions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->q.followUps().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->attempt.improvements().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->practice.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void recordHttpAdapterKeepsTheAuthenticatedOwner() {
        var service=mock(InterviewRecapService.class);var view=recordView();var owner=principal();
        when(service.detail(owner.userId(),record)).thenReturn(view);when(service.list(owner.userId())).thenReturn(List.of(view));
        var controller=new InterviewRecapController(service,mock(InterviewRecapQueueService.class));
        assertThat(controller.get(owner,record).interviewRoundId()).isEqualTo(round);
        assertThat(controller.list(owner).getFirst().id()).isEqualTo(record);
        verify(service).detail(owner.userId(),record);verify(service).list(owner.userId());
    }
    @Test void practiceHttpAdapterKeepsTheAuthenticatedOwner() {
        var service=mock(InterviewReplayService.class);var owner=principal();
        when(service.view(owner.userId(),question)).thenReturn(practiceView());
        var response=new InterviewReplayController(service,mock(AiJobService.class)).view(owner,question);
        assertThat(response.questionId()).isEqualTo(question);verify(service).view(owner.userId(),question);
    }
    @Test void applicationViewsAndServicesCannotDependOnHttpResponses() throws Exception {
        var root=Path.of("src/main/java/com/projectardor/recap/service");
        for(String name:List.of("InterviewRecordView.java","QuestionPracticeView.java","InterviewRecapService.java","InterviewReplayService.java"))
            assertThat(Files.readString(root.resolve(name),StandardCharsets.UTF_8)).doesNotContain("com.projectardor.recap.web");
        assertThat(Files.readString(Path.of("src/main/java/com/projectardor/agent/tools/CareerAgentTools.java"),StandardCharsets.UTF_8))
                .doesNotContain("com.projectardor.recap.web.InterviewRecapResponse");
    }
    private ArdorPrincipal principal() {
        return new ArdorPrincipal(UUID.randomUUID(),"fixture@ardor.invalid","unused",true,UserRole.USER);
    }
}
