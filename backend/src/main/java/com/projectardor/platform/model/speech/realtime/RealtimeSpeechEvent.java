package com.projectardor.platform.model.speech.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One message on the interview voice socket. {@code turn} identifies the generation a frame belongs
 * to and {@code index} the position of a spoken segment inside it, so a browser can drop audio that
 * arrives after the user interrupted or after a newer answer started.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeSpeechEvent(String type, String text, String audio, String detail, String turn, Integer index) {

    public RealtimeSpeechEvent withTurn(String turn, Integer index) {
        return new RealtimeSpeechEvent(type, text, audio, detail, turn, index);
    }

    public RealtimeSpeechEvent withTurn(String turn) {
        return new RealtimeSpeechEvent(type, text, audio, detail, turn, null);
    }

    static RealtimeSpeechEvent signal(String type) {
        return new RealtimeSpeechEvent(type, null, null, null, null, null);
    }

    static RealtimeSpeechEvent text(String type, String text) {
        return new RealtimeSpeechEvent(type, text, null, null, null, null);
    }

    static RealtimeSpeechEvent audio(String audio) {
        return new RealtimeSpeechEvent("audio_delta", null, audio, null, null, null);
    }

    static RealtimeSpeechEvent error(String detail) {
        return new RealtimeSpeechEvent("error", null, null, detail, null, null);
    }
}
