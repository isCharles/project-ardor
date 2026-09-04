package com.projectardor.admin.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminApiConfigRequest(
        @NotBlank @Size(max = 120) String provider,
        @Size(max = 512) String baseUrl,
        @Size(max = 160) String model,
        @Size(max = 4096) String apiKey) {
}
