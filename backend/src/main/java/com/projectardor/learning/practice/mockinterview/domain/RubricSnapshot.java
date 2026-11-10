package com.projectardor.learning.practice.mockinterview.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Freezes a rubric all the way down. A shallow copy leaves nested lists mutable, so a caller could
 * rewrite the criteria after the question was asked; JSONB read back from the database is mutable
 * too. Insertion order is kept so the same rubric always renders the same prompt.
 */
final class RubricSnapshot {
    private RubricSnapshot() {}

    static Map<String, Object> of(Map<String, Object> source) {
        return Collections.unmodifiableMap(copyMap(source));
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), freeze(value)));
        return copy;
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) return Collections.unmodifiableMap(copyMap(map));
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object nested : list) copy.add(freeze(nested));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}