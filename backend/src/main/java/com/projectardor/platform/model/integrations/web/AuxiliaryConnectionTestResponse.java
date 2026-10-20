package com.projectardor.platform.model.integrations.web;

public record AuxiliaryConnectionTestResponse(
        boolean success,
        String message,
        long latencyMs) {
}
