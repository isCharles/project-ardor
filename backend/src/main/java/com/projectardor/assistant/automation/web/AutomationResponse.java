package com.projectardor.assistant.automation.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.assistant.automation.service.AutomationView;

public record AutomationResponse(
        UUID id,
        String title,
        String prompt,
        String cronExpression,
        String timezone,
        boolean enabled,
        boolean deleteAfterRun,
        Instant nextRunAt,
        Instant lastRunAt,
        String lastStatus,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {
    public static AutomationResponse from(AutomationView automation) {
        return new AutomationResponse(automation.id(), automation.title(), automation.prompt(),
                automation.cronExpression(), automation.timezone(), automation.enabled(),
                automation.deleteAfterRun(), automation.nextRunAt(), automation.lastRunAt(),
                automation.lastStatus(), automation.lastError(), automation.createdAt(), automation.updatedAt());
    }
}
