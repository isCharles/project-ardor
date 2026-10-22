package com.projectardor.learning.practice.mockinterview.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.projectardor.learning.practice.mockinterview.domain.InterviewEvaluation;

public record MockInterviewEvaluationView(UUID id, UUID interviewSessionId, BigDecimal overallScore,
        Map<String, Object> evaluation, String modelName, String promptVersion, Instant createdAt) {
    public MockInterviewEvaluationView {
        evaluation = snapshotMap(evaluation);
    }
    public static MockInterviewEvaluationView from(InterviewEvaluation result) {
        return new MockInterviewEvaluationView(result.getId(), result.getInterviewSessionId(), result.getOverallScore(),
                result.getEvaluation(), result.getModelName(), result.getPromptVersion(), result.getCreatedAt());
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
