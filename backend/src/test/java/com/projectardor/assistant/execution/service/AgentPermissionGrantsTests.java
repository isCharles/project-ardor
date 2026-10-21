package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.tools.capability.Capability;

class AgentPermissionGrantsTests {

    @Test
    void digestIsStableAndDoesNotLeakTheArguments() {
        String first = AgentPermissionGrants.digest("{\"memory\":\"现在研二了\"}");
        assertThat(first).hasSize(64).isEqualTo(AgentPermissionGrants.digest("{\"memory\":\"现在研二了\"}"));
        assertThat(first).isNotEqualTo(AgentPermissionGrants.digest("{\"memory\":\"现在研一了\"}"));
        assertThat(first).doesNotContain("研二");
    }

    @Test
    void suggestedLevelFollowsTheCapability() {
        assertThat(AgentPermissionGrants.suggestedLevel(Capability.CREATE)).isEqualTo(1);
        assertThat(AgentPermissionGrants.suggestedLevel(Capability.UPDATE)).isEqualTo(2);
        assertThat(AgentPermissionGrants.suggestedLevel(Capability.EXECUTE)).isEqualTo(3);
    }
}
