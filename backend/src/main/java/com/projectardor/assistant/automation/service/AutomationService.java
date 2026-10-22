package com.projectardor.assistant.automation.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.assistant.automation.domain.Automation;
import com.projectardor.assistant.automation.repository.AutomationRepository;
import com.projectardor.common.web.ResourceNotFoundException;

@Service
public class AutomationService {
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private final AutomationRepository repository;

    public AutomationService(AutomationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Automation create(UUID userId, String title, String prompt, String cronExpression, String timezone) {
        String cleanTitle = required(title, "自动化名称不能为空", 160);
        String cleanPrompt = required(prompt, "自动化要发送的消息不能为空", 20000);
        String cleanCron = required(cronExpression, "执行时间不能为空", 120);
        String zone = validTimezone(timezone);
        Instant next = nextRun(cleanCron, zone, Instant.now());
        return repository.save(Automation.create(userId, cleanTitle, cleanPrompt, cleanCron, zone, next));
    }

    @Transactional(readOnly = true)
    public List<Automation> list(UUID userId) {
        return repository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Automation get(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("自动化任务不存在"));
    }

    @Transactional
    public Automation update(UUID userId, UUID id, String title, String prompt, String cronExpression,
            String timezone, Boolean enabled) {
        Automation automation = get(userId, id);
        String nextTitle = title == null ? null : required(title, "自动化名称不能为空", 160);
        String nextPrompt = prompt == null ? null : required(prompt, "自动化要发送的消息不能为空", 20000);
        String nextCron = cronExpression == null ? null : required(cronExpression, "执行时间不能为空", 120);
        String nextZone = timezone == null ? null : validTimezone(timezone);
        boolean scheduleChanged = nextCron != null || nextZone != null || enabled != null;
        boolean nextEnabled = enabled == null ? automation.isEnabled() : enabled;
        Instant next = null;
        if (nextEnabled) {
            String cron = nextCron == null ? automation.getCronExpression() : nextCron;
            String zone = nextZone == null ? automation.getTimezone() : nextZone;
            next = scheduleChanged ? nextRun(cron, zone, Instant.now()) : automation.getNextRunAt();
        }
        automation.update(nextTitle, nextPrompt, nextCron, nextZone, enabled, next);
        return automation;
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repository.delete(get(userId, id));
    }

    @Transactional(readOnly = true)
    public List<Automation> due(Instant now) {
        return repository.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now);
    }

    /** Atomically takes ownership of one scheduled occurrence before invoking the model. */
    @Transactional
    public boolean claim(Automation automation, Instant now) {
        Instant next = nextRun(automation.getCronExpression(), automation.getTimezone(), now);
        return repository.claim(automation.getId(), automation.getUserId(), now, next) == 1;
    }

    @Transactional
    public void finish(UUID userId, UUID id, boolean success, String error) {
        Automation automation = get(userId, id);
        Instant next = automation.isEnabled()
                ? nextRun(automation.getCronExpression(), automation.getTimezone(), Instant.now())
                : null;
        automation.markFinished(success ? "COMPLETED" : "FAILED", error, next);
    }

    private static Instant nextRun(String cron, String timezone, Instant after) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("执行时间不是有效的 cron 表达式，请使用 6 段格式，例如 0 0 9 * * *");
        }
        ZonedDateTime next = expression.next(ZonedDateTime.ofInstant(after, ZoneId.of(timezone)));
        if (next == null) throw new IllegalArgumentException("执行时间没有下一次运行时间");
        return next.toInstant();
    }

    private static String validTimezone(String timezone) {
        String value = timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone.strip();
        try {
            ZoneId.of(value);
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("时区无效，请使用 IANA 时区，例如 Asia/Shanghai");
        }
    }

    private static String required(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        String clean = value.strip();
        if (clean.length() > maxLength) throw new IllegalArgumentException(message + "（最多 " + maxLength + " 字）");
        return clean;
    }
}
