package com.projectardor.jobsearch.applicationactivity.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Both fields are optional so each total can be edited without overwriting the other. */
public record ApplicationCountRequest(
        @Min(0) @Max(500) Integer greetingCount,
        @Min(0) @Max(500) Integer resumeCount) {}
