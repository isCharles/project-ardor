package com.projectardor.recap.web;

import java.time.Instant;
import jakarta.validation.constraints.Size;

public record InterviewRecapMetadataRequest(
        @Size(max = 160) String company,
        @Size(max = 160) String targetRole,
        Instant occurredAt) {
}
