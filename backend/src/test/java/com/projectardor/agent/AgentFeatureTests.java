package com.projectardor.agent;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentFeatureTests {
    @Test
    void onlyKnownServiceIdsResolveAndLabelsComeFromTheServer() {
        assertThat(AgentFeature.require(AgentFeature.LEARNING.id())).isSameAs(AgentFeature.LEARNING);
        assertThat(AgentFeature.LEARNING.label()).isEqualTo("学习");
        assertThat(AgentFeature.LEARNING.hint()).contains("不是操作授权");
        assertThatThrownBy(() -> AgentFeature.require(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThat(Arrays.stream(AgentFeature.values()).map(AgentFeature::id).distinct()).hasSize(AgentFeature.values().length);
    }
}
