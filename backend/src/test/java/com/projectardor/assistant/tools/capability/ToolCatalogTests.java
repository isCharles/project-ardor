package com.projectardor.assistant.tools.capability;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.tools.AgentToolSchemaCatalog;
import com.projectardor.assistant.tools.modules.AutomationAgentToolProvider;
import com.projectardor.assistant.tools.modules.CalendarAdviceAgentToolProvider;
import com.projectardor.assistant.tools.modules.KnowledgeAgentToolProvider;
import com.projectardor.assistant.tools.modules.ResumeAgentToolProvider;
import com.projectardor.assistant.tools.modules.SchedulingAgentToolProvider;

class ToolCatalogTests {

    @Test
    void everyAgentToolIsRegistered() {
        // Schema discovery must include local adapters, not just the shrinking host.
        // toolTypes() is schema-only: these mocks have no application services to execute.
        Set<String> tools = new AgentToolSchemaCatalog(List.of(
                mock(KnowledgeAgentToolProvider.class, CALLS_REAL_METHODS),
                mock(AutomationAgentToolProvider.class, CALLS_REAL_METHODS),
                mock(CalendarAdviceAgentToolProvider.class, CALLS_REAL_METHODS),
                mock(ResumeAgentToolProvider.class, CALLS_REAL_METHODS),
                mock(SchedulingAgentToolProvider.class, CALLS_REAL_METHODS))).schemas().keySet();

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
