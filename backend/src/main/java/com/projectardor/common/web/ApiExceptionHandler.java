package com.projectardor.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.projectardor.platform.identity.auth.service.DuplicateEmailException;
import com.projectardor.platform.identity.auth.service.RegistrationNotAllowedException;
import com.projectardor.common.security.ExternalHostResolutionException;
import com.projectardor.platform.model.llm.service.LlmCallException;
import com.projectardor.platform.model.speech.service.SpeechCallException;
import com.projectardor.platform.usage.QuotaExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ApiError> quotaExceeded(QuotaExceededException exception) {
        long retryAfter = Math.max(1, java.time.Duration.between(
                java.time.Instant.now(), exception.resetAt()).toSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Retry-After", String.valueOf(retryAfter))
                .body(new ApiError("QUOTA_EXCEEDED", exception.getMessage(), Map.of(
                        "feature", exception.feature().name(),
                        "dimension", exception.reason().name(),
                        "resetAt", exception.resetAt().toString())));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, new ApiError("NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    ResponseEntity<ApiError> duplicateEmail() {
        return error(HttpStatus.CONFLICT, new ApiError("EMAIL_ALREADY_EXISTS", "该邮箱已注册"));
    }

    @ExceptionHandler(RegistrationNotAllowedException.class)
    ResponseEntity<ApiError> registrationNotAllowed(RegistrationNotAllowedException exception) {
        return error(HttpStatus.FORBIDDEN, new ApiError("REGISTRATION_NOT_ALLOWED", exception.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiError> badCredentials() {
        return error(HttpStatus.UNAUTHORIZED, new ApiError("INVALID_CREDENTIALS", "邮箱或密码错误"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, new ApiError("VALIDATION_ERROR", "请检查填写内容", fields));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, new ApiError("FILE_TOO_LARGE", "上传文件或单段录音不能超过 10 MB"));
    }

    @ExceptionHandler({jakarta.validation.ConstraintViolationException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    ResponseEntity<ApiError> methodValidation() {
        return error(HttpStatus.BAD_REQUEST, new ApiError("VALIDATION_ERROR", "请检查请求参数"));
    }

    @ExceptionHandler(ExternalHostResolutionException.class)
    ResponseEntity<ApiError> externalHostResolution(ExternalHostResolutionException exception) {
        return error(HttpStatus.BAD_GATEWAY, new ApiError("EXTERNAL_DNS_TEMPORARY", exception.getMessage(), true));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> illegalArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, new ApiError("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(LlmCallException.class)
    ResponseEntity<ApiError> llmCall(LlmCallException exception) {
        return error(HttpStatus.BAD_GATEWAY, new ApiError(exception.getCode(), exception.getMessage(), exception.isRetryable()));
    }

    @ExceptionHandler(SpeechCallException.class)
    ResponseEntity<ApiError> speechCall(SpeechCallException exception) {
        return error(HttpStatus.BAD_GATEWAY, new ApiError(exception.getCode(), exception.getMessage(), exception.isRetryable()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> illegalState(IllegalStateException exception) {
        return error(HttpStatus.CONFLICT, new ApiError("INVALID_STATE", exception.getMessage()));
    }

    private ResponseEntity<ApiError> error(HttpStatus status, ApiError body) {
        // Errors before SSE starts are JSON, even for older clients accepting only SSE.
        // Explicit Content-Type prevents a second negotiation error from hiding the cause.
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
