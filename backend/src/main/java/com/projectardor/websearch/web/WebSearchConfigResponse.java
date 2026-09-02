package com.projectardor.websearch.web;

import com.projectardor.websearch.domain.WebSearchConfig;

public record WebSearchConfigResponse(boolean configured, String provider, String keyHint) {

    public static WebSearchConfigResponse configured(WebSearchConfig config) {
        return new WebSearchConfigResponse(true, "TAVILY", config.getKeyHint());
    }

    public static WebSearchConfigResponse unconfigured() {
        return new WebSearchConfigResponse(false, "TAVILY", null);
    }
}
