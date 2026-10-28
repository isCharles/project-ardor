package com.projectardor.assistant.execution.service;

/** Bounded, redacted trace previews. Never persist full prompts or tool payloads. */
final class TracePreview {
    static final int DEFAULT_LIMIT = 900;
    private static final String REDACTED = "[redacted]";
    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile(
            "(?i)([\"']?(?:api[-_ ]?key|authorization|token|secret|password)[\"']?\\s*[:=]\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|[^\\s,;}]+)");
    private static final java.util.regex.Pattern BEARER = java.util.regex.Pattern.compile(
            "(?i)\\bBearer\\s+[a-zA-Z0-9._~+/-]+=*");
    private static final java.util.regex.Pattern POSSIBLE_CREDENTIAL = java.util.regex.Pattern.compile(
            "(?i)(?:\\b(?:password|api[-_ ]?key|secret|token)\\s+(?:(?:is|was)\\s+)?\\S+"
                    + "|(?:密码|密钥|令牌)\\s*(?:是|为|[:：=])\\s*\\S+"
                    + "|\\b(?:sk-|ghp_|github_pat_)[a-zA-Z0-9_-]{8,}"
                    + "|\\beyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+)");

    private TracePreview() {}

    static String of(Object value) {
        return of(value, DEFAULT_LIMIT);
    }

    static String of(Object value, int limit) {
        if (value == null || limit < 1) return "";
        String text = String.valueOf(value).replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) return "";
        // Prefer omitting the whole preview to guessing the end of a pasted secret.
        if (POSSIBLE_CREDENTIAL.matcher(text).find()) return REDACTED;
        text = BEARER.matcher(text).replaceAll("Bearer " + REDACTED);
        text = SECRET.matcher(text).replaceAll("$1" + REDACTED);
        if (text.length() <= limit) return text;
        return text.substring(0, Math.max(0, limit - 1)) + "…";
    }

    /** Marks a preview that hit the cap; the stored payload and the model's context are untouched. */
    static boolean isTruncated(String preview) {
        return preview != null && preview.endsWith("…");
    }
}
