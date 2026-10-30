package com.projectardor.assistant.automation.service;

import java.time.Instant;
import java.util.UUID;
import com.projectardor.assistant.automation.domain.Automation;

/** Detached application snapshot; transport adapters must not leak a managed entity. */
public record AutomationView(UUID id, String title, String prompt, String cronExpression,
        String timezone, boolean enabled, boolean deleteAfterRun, Instant nextRunAt, Instant lastRunAt,
        String lastStatus, String lastError, Instant createdAt, Instant updatedAt) {
    static AutomationView from(Automation automation) {
        return new AutomationView(automation.getId(), automation.getTitle(), automation.getPrompt(),
                automation.getCronExpression(), automation.getTimezone(), automation.isEnabled(),
                automation.isDeleteAfterRun(), automation.getNextRunAt(), automation.getLastRunAt(),
                automation.getLastStatus(), automation.getLastError(), automation.getCreatedAt(),
                automation.getUpdatedAt());
    }
}
