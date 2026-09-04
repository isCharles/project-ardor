package com.projectardor.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class InterviewSessionTests {

    @Test
    void preservesRequestedVoiceModality() {
        InterviewSession session = InterviewSession.create(
                UUID.randomUUID(), null, InterviewModality.VOICE, "字节跳动", "后端工程师");

        assertThat(session.getModality()).isEqualTo(InterviewModality.VOICE);
    }

    @Test
    void legacyCreationDefaultsToText() {
        InterviewSession session = InterviewSession.create(
                UUID.randomUUID(), null, null, "后端工程师");

        assertThat(session.getModality()).isEqualTo(InterviewModality.TEXT);
    }
}
