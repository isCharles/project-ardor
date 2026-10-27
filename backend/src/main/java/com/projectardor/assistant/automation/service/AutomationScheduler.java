package com.projectardor.assistant.automation.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectardor.assistant.automation.domain.Automation;
import com.projectardor.assistant.execution.service.CareerAgentService;

/**
 * Runs scheduled prompts through the normal agent pipeline.
 *
 * <p>The prompt is persisted with the automation and sent as a hidden user turn,
 * so the transcript starts with Ardor's answer rather than exposing the trigger.
 * One run at a time; a failed run is recorded and does not retry in the same minute.
 */
@Component
public class AutomationScheduler {
    private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);
    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final AutomationService automations;
    private final CareerAgentService agent;

    public AutomationScheduler(AutomationService automations, CareerAgentService agent) {
        this.automations = automations;
        this.agent = agent;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void runDue() {
        Instant now = Instant.now();
        for (Automation automation : automations.due(now)) {
            if (!automations.claim(automation, now)) continue;
            try {
                execute(automation, now);
                automations.finish(automation.getUserId(), automation.getId(), true, null);
            } catch (RuntimeException exception) {
                log.warn("Automation run failed: id={}, userId={}, error={}", automation.getId(),
                        automation.getUserId(), exception.getMessage());
                try {
                    automations.finish(automation.getUserId(), automation.getId(), false,
                            exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
                } catch (RuntimeException persistFailure) {
                    log.error("Automation failure could not be recorded: id={}", automation.getId(), persistFailure);
                }
            }
        }
    }

    /** Manual test from the Scheduled page; runs now instead of waiting for nextRunAt. */
    public RunResult runNow(UUID userId, UUID automationId) {
        Automation automation = automations.get(userId, automationId);
        try {
            RunResult result = execute(automation, Instant.now());
            automations.finish(userId, automationId, true, null);
            return result;
        } catch (RuntimeException exception) {
            automations.finish(userId, automationId, false,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            throw exception;
        }
    }

    private RunResult execute(Automation automation, Instant now) {
        var conversation = agent.createConversation(automation.getUserId());
        UUID conversationId = conversation.id();
        agent.chatAutomation(automation.getUserId(), conversationId, automation.getPrompt(), automation.getId());
        String title = automation.getTitle() + " · " + TITLE_TIME.format(now);
        agent.renameConversation(automation.getUserId(), conversationId, title);
        return new RunResult(conversationId, title);
    }

    public record RunResult(UUID conversationId, String title) {}
}
