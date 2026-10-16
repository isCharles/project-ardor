package com.projectardor.resume.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/** Frozen at request time; changing a profile while a job waits cannot change its assessment. */
public final class ResumeScoringContext {
    public static final String PROMPT_VERSION = "resume-analysis-v3";
    public static final int TEXT_LIMIT = 50_000;
    private ResumeScoringContext() {}

    public static Map<String, Object> freeze(String role, String description) {
        role = clean(role, 240);
        description = clean(description, 20_000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetRole", role);
        result.put("jobDescription", description);
        result.put("mode", description.isEmpty() ? "GENERAL" : "USER_JD");
        result.put("marketSnapshotVersion", description.isEmpty() ? "general-quality-2026-10-16" : "user-jd-v1:" + hash(description));
        result.put("rubricVersion", ResumeScoringRubric.VERSION);
        result.put("promptVersion", PROMPT_VERSION);
        result.put("textPolicyVersion", "nfc-lines-v1-first-50000");
        result.put("researchDate", "2026-10-16");
        result.put("sources", List.of("https://careerservices.fas.harvard.edu/resources/create-a-strong-resume/"));
        return Map.copyOf(result);
    }

    public static String key(String fullText, Map<String, Object> context, ObjectMapper mapper) {
        // Explicit field order avoids hashing Map implementation iteration order.
        String value = String.join("\n", hash(normalize(fullText)), hash(evaluatedText(fullText)),
                mapper.writeValueAsString(new java.util.TreeMap<>(context)));
        return hash(value);
    }

    public static String evaluatedText(String fullText) {
        String normalized = normalize(fullText);
        return normalized.substring(0, Math.min(normalized.length(), TEXT_LIMIT));
    }

    public static boolean isTruncated(String fullText) { return normalize(fullText).length() > TEXT_LIMIT; }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\t ]+", " ").strip();
    }

    private static String clean(String value, int max) {
        String normalized = normalize(value);
        if (normalized.length() > max) throw new IllegalArgumentException("岗位或JD内容过长");
        return normalized;
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
