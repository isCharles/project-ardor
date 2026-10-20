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
}
