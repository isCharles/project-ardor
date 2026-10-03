package com.projectardor.speech.web;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.interview.domain.InterviewModality;
import com.projectardor.interview.domain.InterviewSession;
import com.projectardor.interview.service.InterviewService;
import com.projectardor.speech.service.SpeechService;
import com.projectardor.usage.QuotaProtected;
import com.projectardor.usage.UsageFeature;

@RestController
@RequestMapping("/api/interviews/{sessionId}/voice")
public class InterviewSpeechController {

    private final InterviewService interviewService;
    private final SpeechService speechService;

    public InterviewSpeechController(InterviewService interviewService, SpeechService speechService) {
        this.interviewService = interviewService;
        this.speechService = speechService;
    }

    @PostMapping(path = "/transcriptions", consumes = "multipart/form-data")
    @QuotaProtected(UsageFeature.VOICE_ASR)
    public SpeechTranscriptionResponse transcribe(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId,
            @RequestPart("audio") MultipartFile audio) {
        requireVoiceSession(principal.userId(), sessionId);
        return new SpeechTranscriptionResponse(speechService.transcribe(principal.userId(), audio));
    }

    @PostMapping("/questions/{questionId}/speech")
    @QuotaProtected(UsageFeature.VOICE_TTS)
    public ResponseEntity<byte[]> questionSpeech(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID sessionId,
            @PathVariable UUID questionId) {
        requireVoiceSession(principal.userId(), sessionId);
        String question = interviewService.getQuestion(principal.userId(), sessionId, questionId).getQuestionText();
        SpeechService.SpeechAudio audio = speechService.synthesize(principal.userId(), question);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_TYPE, audio.mediaType().toString())
                .body(audio.bytes());
    }

    private InterviewSession requireVoiceSession(UUID userId, UUID sessionId) {
        InterviewSession session = interviewService.get(userId, sessionId);
        if (session.getModality() != InterviewModality.VOICE) {
            throw new IllegalStateException("当前面试不是语音面试");
        }
        return session;
    }
}
