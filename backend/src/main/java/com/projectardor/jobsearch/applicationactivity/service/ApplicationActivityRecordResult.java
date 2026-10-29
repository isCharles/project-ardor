package com.projectardor.jobsearch.applicationactivity.service;

/** Result of an Agent record call; an approximate resume count is held until the user confirms. */
public record ApplicationActivityRecordResult(
        ApplicationRhythmView rhythm, boolean confirmationRequired, String message) {}
