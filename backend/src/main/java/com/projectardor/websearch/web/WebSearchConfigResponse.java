package com.projectardor.websearch.web;

import com.projectardor.websearch.domain.WebSearchConfig;

public record WebSearchConfigResponse(boolean configured, String provider, String keyHint,
        String configurationSource, boolean personalOverride) {

    public static WebSearchConfigResponse configured(WebSearchConfig config) {
        return new WebSearchConfigResponse(true, "TAVILY", config.getKeyHint(), "PERSONAL", true);
    }

    public static WebSearchConfigResponse inherited(String keyHint) {
        return new WebSearchConfigResponse(true, "TAVILY", keyHint, "ADMIN", false);
    }

    public static WebSearchConfigResponse unconfigured() {
        return new WebSearchConfigResponse(false, "TAVILY", null, "NONE", false);
    }
}
