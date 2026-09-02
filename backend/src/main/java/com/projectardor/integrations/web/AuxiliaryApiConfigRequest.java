package com.projectardor.integrations.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuxiliaryApiConfigRequest(
        @NotBlank @Size(max = 120) String provider,
        @NotBlank @Size(max = 512) String baseUrl,
        @NotBlank @Size(max = 160) String model,
        @Size(max = 4096) String apiKey) {
}
