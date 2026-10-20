package com.projectardor.platform.model.websearch.web;

public record WebSearchTestResponse(
        boolean success,
        String message,
        long latencyMs,
        Integer usage,
        Integer limit) {
}
