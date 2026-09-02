package com.projectardor.websearch.web;

public record WebSearchTestResponse(
        boolean success,
        String message,
        long latencyMs,
        Integer usage,
        Integer limit) {
}
