package com.projectardor.learning.practice.mockinterview.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The scoring contract fixed before a question is asked. The band text is written by Java, never by
 * the model, so "3" cannot come to mean something different from one question to the next.
 */
public record AdaptiveRubric(String knowledgePoint, int difficulty, List<String> expectedPoints,
        List<String> depthMarkers, List<String> commonMisconceptions, List<String> scoreBands) {

    public static final List<String> SCORE_BANDS = List.of(
            "0：回答错误或完全无关，没有触及该知识点。",
            "1：只说出零散概念，且存在明显误解。",
            "2：方向正确但不完整，缺少关键机制或步骤。",
            "3：回答正确并说清关键原理，覆盖全部必答要点。",
            "4：在 3 的基础上给出场景、边界、取舍或优化，理解深入。");

    /** The 1-5 difficulty scale, fixed here so every prompt and every policy reads the same definitions. */
    public static final List<String> DIFFICULTY_LEVELS = List.of(
            "1：概念与定义，能说出是什么。",
            "2：机制与流程，能说出怎么运作。",
            "3：应用与常见问题，能在真实场景里用对并处理常见故障。",
            "4：深层原理、优化与取舍，能解释为什么这样设计以及代价。",
            "5：复杂场景、系统设计与架构，能在约束冲突时给出方案并论证。");

    public static final int MAX_EXPECTED_POINTS = 8;
    private static final int MAX_ENTRY_LENGTH = 300;

    public AdaptiveRubric {
        if (knowledgePoint == null || knowledgePoint.isBlank()) {
            throw new IllegalArgumentException("评分标准必须绑定知识点");
        }
        if (difficulty < 1 || difficulty > 5) throw new IllegalArgumentException("难度必须在 1 到 5 之间");
        knowledgePoint = knowledgePoint.strip();
        expectedPoints = entries(expectedPoints, MAX_EXPECTED_POINTS, "必答要点");
        if (expectedPoints.isEmpty()) throw new IllegalArgumentException("评分标准至少要有一个必答要点");
        depthMarkers = entries(depthMarkers, 6, "深度标志");
        commonMisconceptions = entries(commonMisconceptions, 6, "常见误解");
        scoreBands = entries(scoreBands, SCORE_BANDS.size(), "分档说明");
        if (scoreBands.size() != SCORE_BANDS.size()) {
            throw new IllegalArgumentException("分档说明必须覆盖 0 到 4 共五档");
        }
    }

    public static AdaptiveRubric create(String knowledgePoint, int difficulty, List<String> expectedPoints,
            List<String> depthMarkers, List<String> commonMisconceptions) {
        return new AdaptiveRubric(knowledgePoint, difficulty, expectedPoints, depthMarkers,
                commonMisconceptions, SCORE_BANDS);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("knowledgePoint", knowledgePoint);
        map.put("difficulty", difficulty);
        map.put("expectedPoints", expectedPoints);
        map.put("depthMarkers", depthMarkers);
        map.put("commonMisconceptions", commonMisconceptions);
        map.put("scoreBands", scoreBands);
        return map;
    }

    /** Reads back exactly what was persisted, so a later band-text revision cannot rescore an old turn. */
    public static AdaptiveRubric fromMap(Map<String, Object> source) {
        if (source == null) throw new IllegalArgumentException("题目没有评分标准");
        return new AdaptiveRubric(text(source.get("knowledgePoint")), integer(source.get("difficulty")),
                strings(source.get("expectedPoints")), strings(source.get("depthMarkers")),
                strings(source.get("commonMisconceptions")), strings(source.get("scoreBands")));
    }

    private static List<String> entries(List<String> values, int limit, String label) {
        if (values == null) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String normalized = value.strip();
            if (normalized.length() > MAX_ENTRY_LENGTH) {
                throw new IllegalArgumentException(label + "过长");
            }
            if (unique.size() >= limit) throw new IllegalArgumentException(label + "最多 " + limit + " 条");
            unique.add(normalized);
        }
        return List.copyOf(unique);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        List<String> values = new ArrayList<>();
        for (Object item : items) if (item != null) values.add(String.valueOf(item));
        return values;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}