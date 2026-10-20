package com.projectardor.recap.web;
import com.projectardor.learning.review.domain.MemoryCardRating;
import jakarta.validation.constraints.NotNull;
public record MemoryCardReviewRequest(@NotNull MemoryCardRating rating) {}
