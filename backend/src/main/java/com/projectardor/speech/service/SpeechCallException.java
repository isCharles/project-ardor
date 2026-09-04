package com.projectardor.speech.service;

public class SpeechCallException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public SpeechCallException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
}
