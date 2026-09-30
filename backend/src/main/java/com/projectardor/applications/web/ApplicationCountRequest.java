package com.projectardor.applications.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ApplicationCountRequest(@NotNull @Min(0) @Max(500) Integer count) {}
