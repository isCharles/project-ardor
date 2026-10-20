package com.projectardor.opportunity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.opportunity.web.OpportunityController;
import com.projectardor.opportunity.web.OpportunityCreateRequest;
import com.projectardor.opportunity.web.OpportunityResponse;
import com.projectardor.opportunity.web.OpportunityRoundRequest;
import com.projectardor.opportunity.web.OpportunityUpdateRequest;
import tools.jackson.databind.json.JsonMapper;

class InterviewApplicationContractTests {
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final UUID process=UUID.randomUUID();
    private InterviewProcessView view() {
        return new InterviewProcessView(process,"Example","Backend","Meeting","SCHEDULED",null,"Invite",2,
                List.of(new InterviewProcessView.Round(UUID.randomUUID(),2,"TECHNICAL",Instant.parse("2099-01-01T01:00:00Z"),null,null,"Note")),
                Instant.parse("2026-10-01T01:00:00Z"),Instant.parse("2026-10-02T01:00:00Z"));
    }
    @Test void applicationAndLegacyHttpViewsKeepExactlyTheSameWireShape() {
        var view=view();
        assertThat(mapper.writeValueAsString(OpportunityResponse.from(view))).isEqualTo(mapper.writeValueAsString(view));
        var json=mapper.readTree(mapper.writeValueAsString(view));
        assertThat(json.propertyNames()).containsExactly("id","company","targetRole","platform","status","jdContent","inviteContent","currentRound","rounds","createdAt","updatedAt");
        assertThat(json.path("rounds").get(0).propertyNames()).containsExactly("id","roundNumber","stage","scheduledAt","occurredAt","durationMinutes","note");
    }
    @Test void transportCommandsPreserveNullOmissionsAndExplicitEmptyClears() {
        var create=new OpportunityCreateRequest("Example","Backend",null,"JD","Invite","TECHNICAL",null,60,"Note");
        assertThat(mapper.writeValueAsString(create.command())).isEqualTo(mapper.writeValueAsString(create));
        var update=new OpportunityUpdateRequest(null,"","",null,"","AWAITING_RESULT");
        assertThat(update.command().company()).isNull();assertThat(update.command().targetRole()).isEmpty();
        assertThat(mapper.writeValueAsString(update.command())).isEqualTo(mapper.writeValueAsString(update));
        var round=new OpportunityRoundRequest(null,"TECHNICAL",null,null,null,"");
        assertThat(round.command().durationMinutes()).isNull();assertThat(round.command().note()).isEmpty();
        assertThat(mapper.writeValueAsString(round.command())).isEqualTo(mapper.writeValueAsString(round));
    }
    @Test void httpAdapterBindsCurrentOwnerAndMapsToTheSameApplicationCommandUsedByAgent() {
        var service=mock(OpportunityService.class);var view=view();
        var principal=new ArdorPrincipal(UUID.randomUUID(),"fixture@ardor.invalid","unused",true,UserRole.USER);
        var request=new OpportunityCreateRequest("Example",null,null,null,null,null,null,null,null);
        when(service.create(principal.userId(),request.command())).thenReturn(view);
        var result=new OpportunityController(service).create(principal,request);
        assertThat(result.id()).isEqualTo(process);
        verify(service).create(principal.userId(),request.command());
    }
    @Test void applicationViewCannotBeChangedByMutatingTheCallerRoundList() {
        var rounds=new ArrayList<>(view().rounds());
        var view=new InterviewProcessView(process,"Example",null,null,"SCHEDULED",null,null,2,rounds,null,null);
        rounds.clear();assertThat(view.rounds()).hasSize(1);
        assertThatThrownBy(() -> view.rounds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void realInterviewApplicationBoundaryDoesNotDependOnItsHttpTransport() throws Exception {
        Path root=Path.of("src/main/java/com/projectardor/opportunity/service");
        for(String name:List.of("OpportunityService.java","InterviewProcessCommands.java","InterviewProcessView.java")) {
            assertThat(Files.readString(root.resolve(name),StandardCharsets.UTF_8)).doesNotContain("com.projectardor.opportunity.web");
        }
        String tools=Files.readString(Path.of("src/main/java/com/projectardor/agent/tools/CareerAgentTools.java"),StandardCharsets.UTF_8);
        assertThat(tools).doesNotContain("com.projectardor.opportunity.web");
    }
}
