package com.projectardor.assistant.automation.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.assistant.automation.domain.Automation;

public record AutomationResponse(
        UUID id,
        String title,
        String prompt,
        String cronExpression,
        String timezone,
        boolean enabled,
        Instant nextRunAt,
        Instant lastRunAt,
        String lastStatus,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {
    public static AutomationResponse from(Automation automation) {
        return new AutomationResponse(automation.getId(), automation.getTitle(), automation.getPrompt(),
                automation.getCronExpression(), automation.getTimezone(), automation.isEnabled(),
                automation.getNextRunAt(), automation.getLastRunAt(), automation.getLastStatus(),
                automation.getLastError(), automation.getCreatedAt(), automation.getUpdatedAt());
    }
}
