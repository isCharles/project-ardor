package com.projectardor.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.projectardor.auth.service.DuplicateEmailException;
import com.projectardor.common.security.ExternalHostResolutionException;
import com.projectardor.llm.service.LlmCallException;
import com.projectardor.speech.service.SpeechCallException;
import com.projectardor.usage.QuotaExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ApiError> quotaExceeded(QuotaExceededException exception) {
        long retryAfter = Math.max(1, java.time.Duration.between(
                java.time.Instant.now(), exception.resetAt()).toSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfter))
                .body(new ApiError("QUOTA_EXCEEDED", exception.getMessage(), Map.of(
                        "feature", exception.feature().name(),
                        "dimension", exception.reason().name(),
                        "resetAt", exception.resetAt().toString())));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError notFound(ResourceNotFoundException exception) {
        return new ApiError("NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError duplicateEmail() {
        return new ApiError("EMAIL_ALREADY_EXISTS", "该邮箱已注册");
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiError badCredentials() {
        return new ApiError("INVALID_CREDENTIALS", "邮箱或密码错误");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return new ApiError("VALIDATION_ERROR", "请检查填写内容", fields);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    ApiError uploadTooLarge() {
        return new ApiError("FILE_TOO_LARGE", "上传文件或单段录音不能超过 10 MB");
    }

    @ExceptionHandler(ExternalHostResolutionException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    ApiError externalHostResolution(ExternalHostResolutionException exception) {
        return new ApiError("EXTERNAL_DNS_TEMPORARY", exception.getMessage(), true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError illegalArgument(IllegalArgumentException exception) {
        return new ApiError("INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(LlmCallException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    ApiError llmCall(LlmCallException exception) {
        return new ApiError(exception.getCode(), exception.getMessage(), exception.isRetryable());
    }

    @ExceptionHandler(SpeechCallException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    ApiError speechCall(SpeechCallException exception) {
        return new ApiError(exception.getCode(), exception.getMessage(), exception.isRetryable());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError illegalState(IllegalStateException exception) {
        return new ApiError("INVALID_STATE", exception.getMessage());
    }
}
