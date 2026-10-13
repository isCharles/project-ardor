package com.projectardor.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MethodValidationResponseTests {
    @Test void invalidMethodParametersUseAStableErrorWithoutEchoingInputs() {
        var error = new ApiExceptionHandler().methodValidation();
        assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.message()).isEqualTo("请检查请求参数");
    }
}
