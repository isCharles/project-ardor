package com.projectardor.assistant.execution.service;

import java.util.Set;
import com.projectardor.assistant.tools.capability.Capability;
import com.projectardor.assistant.tools.capability.ToolCatalog;
import com.projectardor.assistant.tools.capability.ToolModule;

/** Approval policy, independent of the user's preserved custom module grants. */
public enum AgentApprovalMode {
    ASK, AUTO, FULL, CUSTOM;

    private static final Set<String> HIGH_IMPACT = Set.of(
            "create_recurring_task", "update_recurring_task", "record_application_activity", "set_application_rhythm");

    public boolean permitsAutomatically(String tool) {
        var risk = ToolCatalog.describe(tool);
        if (risk == null || risk.capability().requiresProposal()) return false;
        if (risk.capability() == Capability.READ) return true;
        return switch (this) {
            case ASK, CUSTOM -> false;
            case FULL -> true;
            case AUTO -> risk.module() != ToolModule.SYSTEM && risk.capability() != Capability.EXECUTE
                    && !HIGH_IMPACT.contains(tool);
        };
    }

    /** Conservative display for clients that only understand the old numeric contract. */
    public int legacyLevel() {
        return switch (this) { case ASK, CUSTOM -> 0; case AUTO -> 2; case FULL -> 3; };
    }
}
