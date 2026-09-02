package com.projectardor.llm.service;

public class LlmCallException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public LlmCallException(String message) {
        this("LLM_ERROR", message, false, null);
    }

    public LlmCallException(String message, Throwable cause) {
        this("LLM_ERROR", message, false, cause);
    }

    public LlmCallException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
}
