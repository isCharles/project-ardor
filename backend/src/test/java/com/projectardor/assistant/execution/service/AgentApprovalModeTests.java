package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.projectardor.assistant.tools.capability.ToolCatalog;

class AgentApprovalModeTests {
    @Test void askAlwaysRequestsConfirmationForWrites() {
        assertThat(AgentApprovalMode.ASK.permitsAutomatically("list_calendar_tasks")).isTrue();
        assertThat(AgentApprovalMode.ASK.permitsAutomatically("create_calendar_task")).isFalse();
        assertThat(AgentApprovalMode.ASK.permitsAutomatically("update_calendar_task")).isFalse();
    }

    @Test void autoUsesRegisteredOperationImpactNotUserWording() {
        assertThat(AgentApprovalMode.AUTO.permitsAutomatically("create_calendar_task")).isTrue();
        assertThat(AgentApprovalMode.AUTO.permitsAutomatically("update_calendar_task")).isTrue();
        assertThat(AgentApprovalMode.AUTO.permitsAutomatically("update_learning_plan")).isTrue();
        for (String tool : new String[]{"create_recurring_task", "update_recurring_task", "record_application_count",
                "set_application_rhythm", "finish_interview", "cancel_interview", "create_automation", "update_automation"}) {
            assertThat(AgentApprovalMode.AUTO.permitsAutomatically(tool)).as(tool).isFalse();
            assertThat(AgentApprovalMode.FULL.permitsAutomatically(tool)).as(tool).isTrue();
        }
    }

    @Test void noPresetCanAutoApproveDeletionOrUnregisteredTools() {
        for (var mode : AgentApprovalMode.values()) {
            assertThat(mode.permitsAutomatically("future_unregistered_write")).isFalse();
            for (String tool : ToolCatalog.tools()) {
                if (ToolCatalog.describe(tool).capability().requiresProposal()) {
                    assertThat(mode.permitsAutomatically(tool)).as(mode + ":" + tool).isFalse();
                }
            }
        }
    }
}
