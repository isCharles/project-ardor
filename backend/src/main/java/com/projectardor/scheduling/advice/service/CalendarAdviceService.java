package com.projectardor.scheduling.advice.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.scheduling.advice.domain.CalendarAdvice;
import com.projectardor.scheduling.advice.domain.CalendarAdvicePayload;
import com.projectardor.scheduling.advice.repository.CalendarAdviceRepository;
import com.projectardor.scheduling.advice.web.CalendarAdviceResponse;

@Service
public class CalendarAdviceService {
    private static final int MAX_ITEMS = 3;
    private static final int MAX_ITEM = 80;
    private static final int MAX_HEADLINE = 120;
    private final CalendarAdviceRepository repository;

    public CalendarAdviceService(CalendarAdviceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public CalendarAdviceResponse get(UUID userId) {
        CalendarAdvice advice = repository.findById(userId).orElse(null);
        if (advice == null) return CalendarAdviceResponse.empty();
        CalendarAdvicePayload payload = advice.getPayload() != null
                ? normalize(advice.getPayload())
                : legacy(advice);
        return new CalendarAdviceResponse(payload.headline(), payload.days(), payload.weeks(),
                payload.months(), advice.getUpdatedAt());
    }

    @Transactional
    public CalendarAdviceResponse save(UUID userId, String headline, List<String> days,
            List<String> weeks, List<String> months) {
        CalendarAdvicePayload payload = normalize(new CalendarAdvicePayload(headline, days, weeks, months));
        if (payload.days().isEmpty() && payload.weeks().isEmpty() && payload.months().isEmpty()) {
            throw new IllegalArgumentException("至少要给出一个具体时间范围的建议");
        }
        CalendarAdvice advice = repository.findById(userId)
                .map(existing -> { existing.update(payload); return existing; })
                .orElseGet(() -> CalendarAdvice.create(userId, payload));
        CalendarAdvice saved = repository.save(advice);
        return new CalendarAdviceResponse(payload.headline(), payload.days(), payload.weeks(),
                payload.months(), saved.getUpdatedAt());
    }

    @Transactional
    public void delete(UUID userId) {
        repository.deleteById(userId);
    }

    private static CalendarAdvicePayload legacy(CalendarAdvice advice) {
        return new CalendarAdvicePayload(null, splitLegacy(advice.getDays()), splitLegacy(advice.getWeeks()),
                splitLegacy(advice.getMonths()));
    }

    private static CalendarAdvicePayload normalize(CalendarAdvicePayload raw) {
        return new CalendarAdvicePayload(cleanHeadline(raw.headline()), cleanItems(raw.days()),
                cleanItems(raw.weeks()), cleanItems(raw.months()));
    }

    private static String cleanHeadline(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = cleanLine(value);
        return clean.length() <= MAX_HEADLINE ? clean : clean.substring(0, MAX_HEADLINE - 1) + "…";
    }

    private static List<String> cleanItems(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String clean = cleanLine(value);
            if (clean.isBlank()) continue;
            clean = firstSentence(clean);
            if (clean.length() > MAX_ITEM) clean = clean.substring(0, MAX_ITEM - 1) + "…";
            unique.add(clean);
            if (unique.size() == MAX_ITEMS) break;
        }
        return List.copyOf(unique);
    }

    private static List<String> splitLegacy(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> lines = new ArrayList<>();
        for (String line : value.split("\\R+")) {
            if (!line.isBlank()) lines.add(line);
        }
        if (lines.size() <= 1) {
            lines = List.of(value.split("(?<=[。；;！？!?])"));
        }
        return cleanItems(lines);
    }

    private static String cleanLine(String value) {
        return value.replaceAll("\\s+", " ")
                .replaceAll("^\\s*(?:[-*•]|\\d+[.)、])\\s*", "")
                .replace("**", "")
                .strip();
    }

    /** Keep one actionable sentence; long explanations belong in chat, not the calendar card. */
    private static String firstSentence(String value) {
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ("。；;！？!?".indexOf(ch) >= 0) return value.substring(0, index + 1).strip();
        }
        return value;
    }
}
