package com.projectardor.recap.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InterviewRecapMetadataTests {
    @Test void metadataLimitsApplyWithoutControllerBeanValidation() {
        assertThat(InterviewRecapService.cleanMetadata("  Company  ")).isEqualTo("Company");
        assertThat(InterviewRecapService.cleanMetadata(" ")).isNull();
        assertThat(InterviewRecapService.cleanMetadata("x".repeat(160))).hasSize(160);
        assertThatThrownBy(() -> InterviewRecapService.cleanMetadata("x".repeat(161)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
