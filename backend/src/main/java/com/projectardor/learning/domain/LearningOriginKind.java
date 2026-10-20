package com.projectardor.learning.domain;

/** Canonical source identity. LEGACY_RECAP is a read-only historical uncertainty marker. */
public enum LearningOriginKind {
    MANUAL, AGENT, RESUME, KNOWLEDGE, INTERVIEW_RECORD, INTERVIEW_QUESTION, LEGACY_RECAP;

    public LearningSourceType legacyType() {
        return switch (this) {
            case INTERVIEW_RECORD, INTERVIEW_QUESTION, LEGACY_RECAP -> LearningSourceType.RECAP;
            default -> LearningSourceType.valueOf(name());
        };
    }
}
