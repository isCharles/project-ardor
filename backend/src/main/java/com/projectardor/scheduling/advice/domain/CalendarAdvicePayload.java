package com.projectardor.scheduling.advice.domain;

import java.util.List;

/** Structured, intentionally small advice payload; one sentence per item. */
public record CalendarAdvicePayload(String headline, List<String> days, List<String> weeks, List<String> months) {
}
