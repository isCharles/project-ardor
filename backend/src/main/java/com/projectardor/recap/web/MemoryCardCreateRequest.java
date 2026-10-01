package com.projectardor.recap.web;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.projectardor.recap.domain.MemoryCardSource;
import jakarta.validation.constraints.*;
public record MemoryCardCreateRequest(MemoryCardSource sourceType, @Size(max=240) String sourceLabel,
        @Size(max=1000) String sourceUrl, @NotBlank @Size(max=12000) String front,
        @NotBlank @Size(max=20000) String back, @Size(max=12) List<@Size(max=80) String> tags,
        Instant nextReviewAt, UUID recapQuestionId) {}
