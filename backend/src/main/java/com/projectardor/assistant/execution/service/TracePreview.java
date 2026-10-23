package com.projectardor.assistant.execution.service;

/** Bounded, redacted trace previews. Never persist full prompts or tool payloads. */
final class TracePreview {
    static final int DEFAULT_LIMIT = 900;
    private static final String REDACTED = "[redacted]";
    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile(
            "(?i)(api[-_ ]?key|authorization|bearer|token|secret|password)\\s*[:=]\\s*[^\\s,;]+");

    private TracePreview() {}

    static String of(Object value) {
        return of(value, DEFAULT_LIMIT);
    }

    static String of(Object value, int limit) {
        if (value == null) return "";
        String text = String.valueOf(value).replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) return "";
        text = SECRET.matcher(text).replaceAll("$1=" + REDACTED);
        if (text.length() <= limit) return text;
        return text.substring(0, Math.max(0, limit - 1)) + "…";
    }
}

