package com.projectardor.jobsearch.applicationactivity.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Wording guard for Agent writes: an approximate resume count must not become an exact total. */
public final class ApplicationRhythmIntent {
    private ApplicationRhythmIntent() {}

    private static final Pattern RESUME = Pattern.compile("简历|resume|résumé", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPROXIMATE = Pattern.compile(
            "约(?:为|是|有)?\\s*(?:[0-9]+|[一二三四五六七八九十百两]+(?=\\s*(?:份|个|次|条|位|家|封)))"
                    + "|大约|差不多|左右|about|around|approximately|roughly|~",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAUSE = Pattern.compile(
            "[。！？!?;；\\n，,、]|\\band\\b|并且|同时|以及|另外", Pattern.CASE_INSENSITIVE);

    public static boolean approximateResumeCount(String request) {
        if (request == null || request.isBlank()) return false;
        for (String clause : CLAUSE.split(request.toLowerCase(Locale.ROOT))) {
            if (RESUME.matcher(clause).find() && APPROXIMATE.matcher(clause).find()) return true;
        }
        return false;
    }
}
