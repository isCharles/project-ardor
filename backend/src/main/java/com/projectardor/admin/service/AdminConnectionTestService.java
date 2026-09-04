package com.projectardor.admin.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.projectardor.admin.domain.SystemApiServiceType;
import com.projectardor.admin.web.AdminApiConfigRequest;
import com.projectardor.integrations.service.AuxiliaryConnectionTestService;
import com.projectardor.llm.service.LlmConnectionTestService;
import com.projectardor.websearch.service.TavilySearchService;
import com.projectardor.speech.service.SpeechService;
import com.projectardor.integrations.domain.AuxiliaryServiceType;
import com.projectardor.integrations.web.AuxiliaryConnectionTestResponse;

@Service
public class AdminConnectionTestService {
    private final SystemApiConfigService configService;
    private final LlmConnectionTestService llmTestService;
    private final AuxiliaryConnectionTestService auxiliaryTestService;
    private final TavilySearchService tavilySearchService;
    private final SpeechService speechService;

    public AdminConnectionTestService(SystemApiConfigService configService,
            LlmConnectionTestService llmTestService,
            AuxiliaryConnectionTestService auxiliaryTestService,
            TavilySearchService tavilySearchService,
            SpeechService speechService) {
        this.configService = configService;
        this.llmTestService = llmTestService;
        this.auxiliaryTestService = auxiliaryTestService;
        this.tavilySearchService = tavilySearchService;
        this.speechService = speechService;
    }

    public Object test(UUID adminId, SystemApiServiceType type, AdminApiConfigRequest request) {
        return switch (type) {
            case PRIMARY_LLM -> llmTestService.test(adminId, configService.llmForTest(type, request));
            case WEB_SEARCH -> tavilySearchService.testApiKey(configService.webSearchKeyForTest(request));
            case ASR, TTS -> testSpeech(type, request);
            default -> auxiliaryTestService.test(configService.auxiliaryForTest(type, request));
        };
    }

    private AuxiliaryConnectionTestResponse testSpeech(
            SystemApiServiceType type,
            AdminApiConfigRequest request) {
        long startedAt = System.nanoTime();
        speechService.probe(
                configService.auxiliaryForTest(type, request),
                AuxiliaryServiceType.valueOf(type.name()));
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new AuxiliaryConnectionTestResponse(
                true,
                type == SystemApiServiceType.ASR ? "语音识别可用" : "语音合成可用",
                latencyMs);
    }
}
