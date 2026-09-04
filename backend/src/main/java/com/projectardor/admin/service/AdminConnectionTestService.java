package com.projectardor.admin.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.projectardor.admin.domain.SystemApiServiceType;
import com.projectardor.admin.web.AdminApiConfigRequest;
import com.projectardor.integrations.service.AuxiliaryConnectionTestService;
import com.projectardor.llm.service.LlmConnectionTestService;
import com.projectardor.websearch.service.TavilySearchService;

@Service
public class AdminConnectionTestService {
    private final SystemApiConfigService configService;
    private final LlmConnectionTestService llmTestService;
    private final AuxiliaryConnectionTestService auxiliaryTestService;
    private final TavilySearchService tavilySearchService;

    public AdminConnectionTestService(SystemApiConfigService configService,
            LlmConnectionTestService llmTestService,
            AuxiliaryConnectionTestService auxiliaryTestService,
            TavilySearchService tavilySearchService) {
        this.configService = configService;
        this.llmTestService = llmTestService;
        this.auxiliaryTestService = auxiliaryTestService;
        this.tavilySearchService = tavilySearchService;
    }

    public Object test(UUID adminId, SystemApiServiceType type, AdminApiConfigRequest request) {
        return switch (type) {
            case PRIMARY_LLM -> llmTestService.test(adminId, configService.llmForTest(type, request));
            case WEB_SEARCH -> tavilySearchService.testApiKey(configService.webSearchKeyForTest(request));
            default -> auxiliaryTestService.test(configService.auxiliaryForTest(type, request));
        };
    }
}
