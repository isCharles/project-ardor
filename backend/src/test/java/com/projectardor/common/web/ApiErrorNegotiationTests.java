package com.projectardor.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.projectardor.common.security.ExternalHostResolutionException;
import com.projectardor.platform.identity.auth.service.DuplicateEmailException;
import com.projectardor.platform.model.llm.service.LlmCallException;
import com.projectardor.platform.model.speech.service.SpeechCallException;
import com.projectardor.platform.usage.QuotaExceededException;
import com.projectardor.platform.usage.QuotaStore;
import com.projectardor.platform.usage.UsageFeature;

class ApiErrorNegotiationTests {
    private static final Instant RESET = Instant.parse("2099-01-01T00:00:00Z");
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @ParameterizedTest
    @CsvSource({
            "missing,404,NOT_FOUND,false", "duplicate,409,EMAIL_ALREADY_EXISTS,false",
            "credentials,401,INVALID_CREDENTIALS,false", "validation,400,VALIDATION_ERROR,false",
            "upload,413,FILE_TOO_LARGE,false", "argument,400,INVALID_REQUEST,false",
            "state,409,INVALID_STATE,false", "dns,502,EXTERNAL_DNS_TEMPORARY,true",
            "llm,502,LLM_TIMEOUT,true", "speech,502,ASR_INVALID_RESPONSE,false"
    })
    void preStreamErrorsKeepTheirJsonStatusAndRetryabilityForOldClients(
            String kind, int status, String code, boolean retryable) throws Exception {
        mvc.perform(get("/probe/" + kind).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.retryable").value(retryable))
                .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.fields").exists());
        mvc.perform(get("/json/" + kind).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.retryable").value(retryable));
    }

    @ParameterizedTest
    @CsvSource({"MONTHLY_LIMIT", "TOKEN_LIMIT", "USER_MINUTE_LIMIT", "GLOBAL_MINUTE_LIMIT"})
    void quotaErrorsKeepResetDimensionAndRetryAfterBeforeSseStarts(String reason) throws Exception {
        var response = mvc.perform(get("/probe/" + reason).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isTooManyRequests()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.fields.feature").value("AGENT_CHAT"))
                .andExpect(jsonPath("$.fields.dimension").value(reason))
                .andExpect(jsonPath("$.fields.resetAt").value(RESET.toString()))
                .andReturn().getResponse();
        assertThat(Long.parseLong(response.getHeader("Retry-After"))).isPositive();
    }

    @Test
    void bodyValidationAlsoReturnsJsonToStrictSseClients() throws Exception {
        mvc.perform(post("/probe/body").accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.value").isNotEmpty());
    }

    @Test
    void dualAcceptKeepsSuccessfulSseRatherThanSwitchingItToJson() throws Exception {
        mvc.perform(get("/probe/success").header("Accept", "text/event-stream, application/json"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("data: {\"type\":\"done\"}\n\n"));
    }

    @RestController
    static class Probe {
        record Input(@jakarta.validation.constraints.NotBlank String value) {}

        @PostMapping(value = "/probe/body", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public String validated(@jakarta.validation.Valid @RequestBody Input input) { return input.value(); }

        @GetMapping(value = "/json/{kind}", produces = MediaType.APPLICATION_JSON_VALUE)
        public String json(@PathVariable String kind) { return call(kind); }

        @GetMapping(value = "/probe/{kind}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public String call(@PathVariable String kind) {
            if (kind.endsWith("LIMIT")) throw new QuotaExceededException(UsageFeature.AGENT_CHAT,
                    QuotaStore.Decision.valueOf(kind), RESET);
            return switch (kind) {
                case "missing" -> throw new ResourceNotFoundException("Fixture missing");
                case "duplicate" -> throw new DuplicateEmailException();
                case "credentials" -> throw new BadCredentialsException("Do not echo details");
                case "validation" -> throw new jakarta.validation.ConstraintViolationException(Set.of());
                case "upload" -> throw new MaxUploadSizeExceededException(10);
                case "argument" -> throw new IllegalArgumentException("Fixture invalid");
                case "state" -> throw new IllegalStateException("Fixture conflict");
                case "dns" -> throw new ExternalHostResolutionException("Fixture DNS", null);
                case "llm" -> throw new LlmCallException("LLM_TIMEOUT", "Fixture timeout", true, null);
                case "speech" -> throw new SpeechCallException("ASR_INVALID_RESPONSE", "Fixture invalid", false, null);
                default -> "data: {\"type\":\"done\"}\n\n";
            };
        }
    }
}
