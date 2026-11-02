package com.projectardor.platform.model.llm.service;

public class LlmCallException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    /** How long the caller should wait, when the refusal or the provider says so; null means unknown. */
    private final Integer retryAfterSeconds;

    public LlmCallException(String message) {
        this("LLM_ERROR", message, false, null, null);
    }

    public LlmCallException(String message, Throwable cause) {
        this("LLM_ERROR", message, false, null, cause);
    }

    public LlmCallException(String code, String message, boolean retryable, Throwable cause) {
        this(code, message, retryable, null, cause);
    }

    public LlmCallException(String code, String message, boolean retryable, Integer retryAfterSeconds,
            Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
}
