package com.projectardor.common.web;

import java.util.Map;

public record ApiError(String code, String message, Map<String, String> fields, boolean retryable) {

    public ApiError(String code, String message) {
        this(code, message, Map.of(), false);
    }

    public ApiError(String code, String message, Map<String, String> fields) {
        this(code, message, fields, false);
    }

    public ApiError(String code, String message, boolean retryable) {
        this(code, message, Map.of(), retryable);
    }
}
