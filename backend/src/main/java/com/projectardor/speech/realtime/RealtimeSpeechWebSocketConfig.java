package com.projectardor.speech.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RealtimeSpeechWebSocketConfig implements WebSocketConfigurer {

    private final RealtimeSpeechWebSocketHandler handler;

    public RealtimeSpeechWebSocketConfig(RealtimeSpeechWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/interviews/*/voice")
                .setAllowedOrigins("http://localhost:3000", "http://127.0.0.1:3000");
    }
}
