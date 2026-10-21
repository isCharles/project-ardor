package com.projectardor.learning.service;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.web.LearningPlanCreateRequest;
import tools.jackson.databind.json.JsonMapper;

class LearningPayloadCompatibilityTests {
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final UUID request=UUID.fromString("90000000-0000-0000-0000-000000000001");

    @Test void oldSixFieldPayloadRemainsByteForByteStableForPersistedJobHashes() {
        var input=new LearningPlanCreateRequest("Legacy lesson",null,LearningSourceType.MANUAL,null,null,request);
        assertThat(mapper.writeValueAsString(input)).isEqualTo("{\"concept\":\"Legacy lesson\",\"reason\":null,\"sourceType\":\"MANUAL\",\"sourceId\":null,\"scheduledAt\":null,\"requestId\":\"90000000-0000-0000-0000-000000000001\"}");
        assertThat(mapper.writeValueAsString(input.command())).isEqualTo(mapper.writeValueAsString(input));
    }

    @Test void explicitContextStillSurvivesJobSerializationAndDeserialization() {
        UUID question=UUID.randomUUID(),target=UUID.randomUUID(),round=UUID.randomUUID();
        var input=new LearningPlanCreateRequest("Canonical lesson",null,LearningSourceType.RECAP,question,null,request,
                LearningOriginKind.INTERVIEW_QUESTION,question,target,round);
        var decoded=mapper.readValue(mapper.writeValueAsString(input),LearningPlanCreateRequest.class);
        assertThat(decoded.context()).isEqualTo(input.context());
        assertThat(mapper.writeValueAsString(input.command())).isEqualTo(mapper.writeValueAsString(input));
        assertThat(mapper.readValue(mapper.writeValueAsString(input),LearningCreationCommand.class).context()).isEqualTo(input.context());
    }

    @Test void evaluationAndReplayApplicationCommandsKeepPersistedTransportPayloadsStable() {
        var evaluation=new com.projectardor.learning.web.LearningEvaluationQueueRequest(request,2,java.util.List.of("Saved answer"));
        String evaluationJson=mapper.writeValueAsString(evaluation);
        assertThat(mapper.writeValueAsString(evaluation.command())).isEqualTo(evaluationJson);
        assertThat(mapper.readValue(evaluationJson,LearningEvaluationCommand.class)).isEqualTo(evaluation.command());
        for(UUID retest:new UUID[]{null,UUID.randomUUID()}) {
            var replay=new com.projectardor.learning.practice.question.web.InterviewReplaySubmitRequest(request,"Saved answer",retest);
            String replayJson=mapper.writeValueAsString(replay);
            assertThat(mapper.writeValueAsString(replay.command())).isEqualTo(replayJson);
            assertThat(mapper.readValue(replayJson,com.projectardor.learning.practice.question.service.ReplayEvaluationCommand.class)).isEqualTo(replay.command());
        }
    }
}
