package com.projectardor.platform.model.speech.realtime;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RealtimeSpeechWebSocketConfig implements WebSocketConfigurer {

    static final String LOCAL_ORIGINS = "http://localhost:3000,http://127.0.0.1:3000";

    private final RealtimeSpeechWebSocketHandler handler;
    private final String[] allowedOrigins;

    public RealtimeSpeechWebSocketConfig(RealtimeSpeechWebSocketHandler handler,
            @Value("${app.realtime.allowed-origins:" + LOCAL_ORIGINS + "}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = parseOrigins(allowedOrigins);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/interviews/*/voice", "/ws/interviews/voice/new")
                .setAllowedOrigins(allowedOrigins);
    }

    static String[] parseOrigins(String configured) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        if (configured != null) {
            Arrays.stream(configured.split(","))
                    .map(String::strip)
                    .filter(origin -> !origin.isEmpty())
                    .forEach(origins::add);
        }
        if (origins.isEmpty()) origins.addAll(Arrays.asList(LOCAL_ORIGINS.split(",")));
        return origins.toArray(String[]::new);
    }
}
