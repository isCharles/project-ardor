package com.projectardor.integrations.web;

public record AuxiliaryConnectionTestResponse(
        boolean success,
        String message,
        long latencyMs) {
}
