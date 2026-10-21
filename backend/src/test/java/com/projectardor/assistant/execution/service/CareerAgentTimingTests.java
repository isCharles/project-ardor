package com.projectardor.assistant.execution.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CareerAgentTimingTests {
    @Test
    void firstOutputDelayIsMeasuredFromRequestStartNotCompletion() {
        long startedAt = 1_000_000_000L;
        long firstPartialAt = startedAt + 454_910_000_000L;
        assertThat(CareerAgentService.firstPartialDelayMs(startedAt, firstPartialAt)).isEqualTo(454910);
        assertThat(CareerAgentService.firstPartialDelayMs(startedAt, 0)).isEqualTo(-1);
        assertThat(CareerAgentService.firstPartialDelayMs(startedAt, startedAt - 1)).isZero();
    }
}
