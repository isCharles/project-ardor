package com.projectardor.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import com.projectardor.assistant.tools.modules.AgentToolContext;
import com.projectardor.assistant.tools.modules.LearningAgentToolProvider;
import com.projectardor.learning.plan.domain.LearningOriginKind;
import com.projectardor.learning.plan.domain.LearningSourceType;
import com.projectardor.learning.plan.service.LearningCreationCommand;
import com.projectardor.learning.plan.service.LearningPlanService;

class LearningAgentToolProviderTests {
    private final UUID owner = UUID.randomUUID(), action = UUID.randomUUID();
    private final LearningPlanService service = mock(LearningPlanService.class);
    private final List<LearningCreationCommand> proposals = new ArrayList<>();

    private LearningAgentToolProvider.Bound bound(UUID trustedAction) {
        return new LearningAgentToolProvider(service).bind(new AgentToolContext(owner,
                (kind, id, label, detail, endpoint) -> Map.of(), (kind, value) -> "unused", trustedAction,
                input -> { proposals.add(input); return Map.of("status", "CONFIRMATION_REQUIRED"); }));
    }

    @Test void legacyIdentityAndNormalizationRemainByteCompatible() {
        UUID source = UUID.randomUUID();
        var bound = bound(action);
        bound.createLearningPlan(" JVM ", " gap ", "2028-04-01T09:00:00+08:00", "RECAP", source.toString());
        var command = proposals.getFirst();
        String seed = owner + ":" + action + ":JVM:gap:RECAP:" + source + ":2028-04-01T01:00:00Z";
        assertThat(command.requestId()).isEqualTo(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
        assertThat(command.concept()).isEqualTo("JVM");
        assertThat(command.reason()).isEqualTo("gap");
        assertThat(command.scheduledAt()).isEqualTo(Instant.parse("2028-04-01T01:00:00Z"));
        assertThat(command.originKind()).isNull();
        assertThat(command.originResourceId()).isNull();
        assertThat(command.sourceType()).isEqualTo(LearningSourceType.RECAP);
        verifyNoInteractions(service);
    }

    @Test void explicitSourceAndPreparationTargetStayIndependentAndFramed() {
        UUID source = UUID.randomUUID(), target = UUID.randomUUID(), round = UUID.randomUUID();
        var bound = bound(action);
        bound.createLearningPlan("JVM", null, null, null, source.toString(), "INTERVIEW_QUESTION",
                target.toString(), round.toString());
        var command = proposals.getFirst();
        assertThat(command.originKind()).isEqualTo(LearningOriginKind.INTERVIEW_QUESTION);
        assertThat(command.sourceType()).isEqualTo(LearningSourceType.RECAP);
        assertThat(command.originResourceId()).isEqualTo(source);
        assertThat(command.targetInterviewId()).isEqualTo(target);
        assertThat(command.targetRoundId()).isEqualTo(round);
        var seed = new StringBuilder("LEARNING_CONTEXT_V1:");
        for (Object value : new Object[]{owner,action,"JVM",null,LearningSourceType.RECAP,source,null,command.context().signature()}) {
            String part = value == null ? null : value.toString();
            seed.append(part == null ? "-1:" : part.length() + ":" + part);
        }
        assertThat(command.requestId()).isEqualTo(UUID.nameUUIDFromBytes(seed.toString().getBytes(StandardCharsets.UTF_8)));
        bound.createLearningPlan("JVM", null, null, null, source.toString(), "INTERVIEW_QUESTION", null, null);
        assertThat(proposals.get(1).targetInterviewId()).isNull();
        assertThat(proposals.get(1).requestId()).isNotEqualTo(command.requestId());
        verifyNoInteractions(service);
    }

    @Test void defaultAndLegacySyncCreationRemainProposalsOnly() {
        var bound = bound(null);
        bound.createLearningPlan("JVM", " ", null, null, null);
        bound.createLearningPlan("JVM", " ", null, null, null);
        assertThat(proposals).hasSize(2).allSatisfy(command -> {
            assertThat(command.sourceType()).isEqualTo(LearningSourceType.AGENT);
            assertThat(command.reason()).isNull();
            assertThat(command.scheduledAt()).isNull();
            assertThat(command.targetInterviewId()).isNull();
        });
        assertThat(proposals.get(0).requestId()).isNotEqualTo(proposals.get(1).requestId());
        verifyNoInteractions(service);
    }

    @Test void missingHostCapabilityFailsClosedInsteadOfCreating() {
        var bound = new LearningAgentToolProvider(service).bind(new AgentToolContext(owner,
                (kind, id, label, detail, endpoint) -> Map.of(), (kind, value) -> "unused"));
        assertThatThrownBy(() -> bound.createLearningPlan("JVM", null, null, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not bound");
        verifyNoInteractions(service);
    }

    @Test void malformedOriginsTargetsAndDatesNeverProduceProposals() {
        var bound = bound(action);
        assertThatThrownBy(() -> bound.createLearningPlan("JVM", null, null, null, null, "LEGACY_RECAP", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("明确来源");
        assertThatThrownBy(() -> bound.createLearningPlan("JVM", null, null, "RESUME", null, "KNOWLEDGE", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不一致");
        assertThatThrownBy(() -> bound.createLearningPlan("JVM", null, null, null, null, null, null, UUID.randomUUID().toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("目标面试");
        for (String date : List.of("tomorrow", "2028-04-01T09:00:00", "2000-01-01T00:00:00Z")) {
            assertThatThrownBy(() -> bound.createLearningPlan("JVM", null, date, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> bound.createLearningPlan("JVM", null, null, null, "invalid"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("来源 ID");
        assertThat(proposals).isEmpty(); verifyNoInteractions(service);
    }

    @Test void conceptAndReasonLimitsRemainBeforeHostCallback() {
        var bound = bound(action);
        for (String concept : new String[]{null, " ", "a".repeat(161)}) {
            assertThatThrownBy(() -> bound.createLearningPlan(concept, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("160");
        }
        assertThatThrownBy(() -> bound.createLearningPlan("JVM", "a".repeat(4001), null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("原因过长");
        bound.createLearningPlan("a".repeat(160), "a".repeat(4000), null, null, null);
        assertThat(proposals).hasSize(1); verifyNoInteractions(service);
    }
}
