package com.projectardor.recap.web;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemoryCardUpdateRequest(
        @NotBlank @Size(max = 12000) String front,
        @NotBlank @Size(max = 20000) String back,
        @Size(max = 12) List<@Size(max = 80) String> tags) {
}
