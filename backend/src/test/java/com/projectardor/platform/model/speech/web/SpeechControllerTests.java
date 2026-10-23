package com.projectardor.platform.model.speech.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.model.speech.service.SpeechService;

class SpeechControllerTests {

    @Test
    void chatDictationUsesTheAuthenticatedUsersAsrConfiguration() {
        SpeechService speech = mock(SpeechService.class);
        var controller = new SpeechController(speech);
        UUID user = UUID.randomUUID();
        var principal = new ArdorPrincipal(user, "voice@ardor.invalid", "", true, UserRole.USER);
        var audio = new MockMultipartFile("audio", "chat-audio.webm", "audio/webm", new byte[] {1, 2, 3});
        when(speech.transcribe(user, audio)).thenReturn("  明天下午三点提醒我  ");

        var response = controller.transcribe(principal, audio);

        assertThat(response.text()).isEqualTo("  明天下午三点提醒我  ");
    }
}
