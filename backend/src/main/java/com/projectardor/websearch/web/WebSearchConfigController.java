package com.projectardor.websearch.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.websearch.service.TavilySearchService;
import com.projectardor.websearch.service.WebSearchConfigService;
import com.projectardor.usage.QuotaProtected;
import com.projectardor.usage.UsageFeature;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/settings/web-search")
public class WebSearchConfigController {

    private final WebSearchConfigService configService;
    private final TavilySearchService searchService;

    public WebSearchConfigController(WebSearchConfigService configService, TavilySearchService searchService) {
        this.configService = configService;
        this.searchService = searchService;
    }

    @GetMapping
    public WebSearchConfigResponse get(@AuthenticationPrincipal ArdorPrincipal principal) {
        return configService.get(principal.userId());
    }

    @PutMapping
    public WebSearchConfigResponse update(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody WebSearchConfigRequest request) {
        return configService.upsert(principal.userId(), request);
    }

    @PostMapping("/test")
    @QuotaProtected(UsageFeature.CONNECTION_TEST)
    public WebSearchTestResponse test(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody WebSearchConfigRequest request) {
        return searchService.test(principal.userId(), request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal) {
        configService.delete(principal.userId());
    }
}
