package com.projectardor.materials.resume.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectardor.materials.resume.domain.ResumeAnalysis;

/** Detached, read-only analysis context; never exposes storage or owner credentials. */
public record ResumeAnalysisSnapshot(UUID id, UUID resumeId, Map<String, Object> analysis,
        String modelName, String promptVersion, Instant createdAt) {
    public ResumeAnalysisSnapshot {
        analysis = snapshotMap(analysis);
    }

    public static ResumeAnalysisSnapshot from(ResumeAnalysis source) {
        return new ResumeAnalysisSnapshot(source.getId(), source.getResumeId(), source.getAnalysis(),
                source.getModelName(), source.getPromptVersion(), source.getCreatedAt());
    }

    private static Map<String, Object> snapshotMap(Map<String, Object> source) {
        if (source == null) return null;
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(key, snapshot(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object snapshot(Object value) {
        if (value instanceof Map<?, ?> source) {
            var copy = new LinkedHashMap<Object, Object>();
            source.forEach((key, item) -> copy.put(key, snapshot(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> source) {
            var copy = new ArrayList<Object>();
            source.forEach(item -> copy.add(snapshot(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
