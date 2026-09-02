package com.projectardor.recap.web;
import com.projectardor.recap.domain.MemoryCardRating;
import jakarta.validation.constraints.NotNull;
public record MemoryCardReviewRequest(@NotNull MemoryCardRating rating) {}
