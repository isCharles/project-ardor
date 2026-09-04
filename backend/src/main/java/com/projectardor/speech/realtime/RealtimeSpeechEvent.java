package com.projectardor.speech.realtime;

public record RealtimeSpeechEvent(String type, String text, String audio, String detail) {

    static RealtimeSpeechEvent signal(String type) {
        return new RealtimeSpeechEvent(type, null, null, null);
    }

    static RealtimeSpeechEvent text(String type, String text) {
        return new RealtimeSpeechEvent(type, text, null, null);
    }

    static RealtimeSpeechEvent audio(String audio) {
        return new RealtimeSpeechEvent("audio_delta", null, audio, null);
    }

    static RealtimeSpeechEvent error(String detail) {
        return new RealtimeSpeechEvent("error", null, null, detail);
    }
}
