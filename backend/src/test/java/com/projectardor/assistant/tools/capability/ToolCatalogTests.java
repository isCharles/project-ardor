package com.projectardor.assistant.tools.capability;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.Tool;
import com.projectardor.assistant.tools.CareerAgentTools;

class ToolCatalogTests {

    @Test
    void everyAgentToolIsRegistered() {
        Set<String> tools = Arrays.stream(CareerAgentTools.BoundCareerTools.class.getMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(method -> method.getAnnotation(Tool.class).name())
                .collect(Collectors.toSet());

        assertThat(tools).hasSizeGreaterThan(50);
        assertThat(tools).allSatisfy(tool -> assertThat(ToolCatalog.describe(tool))
                .as("tool %s must have a capability entry", tool).isNotNull());
    }

    @Test
    void deleteToolsAreNeverAutoApproved() {
        for (String tool : ToolCatalog.tools()) {
            boolean looksDestructive = tool.startsWith("delete_") || tool.equals("clear_all_memories");
            if (looksDestructive) {
                assertThat(ToolCatalog.describe(tool).capability()).isEqualTo(Capability.DELETE);
            }
        }
        assertThat(ToolCatalog.describe("add_memory").capability()).isEqualTo(Capability.CREATE);
        assertThat(ToolCatalog.describe("update_memory").capability()).isEqualTo(Capability.UPDATE);
        assertThat(ToolCatalog.describe("finish_interview").capability()).isEqualTo(Capability.EXECUTE);
    }

    @Test
    void everyCapabilityNeedsAReasonableLevel() {
        assertThat(Capability.READ.minimumLevel()).isZero();
        assertThat(Capability.CREATE.minimumLevel()).isEqualTo(1);
        assertThat(Capability.UPDATE.minimumLevel()).isEqualTo(2);
        assertThat(Capability.EXECUTE.minimumLevel()).isEqualTo(3);
        assertThat(Capability.DELETE.requiresProposal()).isTrue();
    }
}
