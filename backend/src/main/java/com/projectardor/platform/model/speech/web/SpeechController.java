package com.projectardor.platform.model.speech.web;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.model.speech.service.SpeechService;
import com.projectardor.platform.usage.QuotaProtected;
import com.projectardor.platform.usage.UsageFeature;

/** Generic chat dictation; interview voice questions remain under the interview controller. */
@RestController
@RequestMapping("/api/speech")
public class SpeechController {

    private final SpeechService speechService;

    public SpeechController(SpeechService speechService) {
        this.speechService = speechService;
    }

    @PostMapping(path = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @QuotaProtected(UsageFeature.VOICE_ASR)
    public SpeechTranscriptionResponse transcribe(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @RequestPart("audio") MultipartFile audio) {
        return new SpeechTranscriptionResponse(speechService.transcribe(principal.userId(), audio));
    }
}
