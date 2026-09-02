package com.projectardor.websearch.web;

import jakarta.validation.constraints.Size;

public record WebSearchConfigRequest(
        @Size(max = 4096, message = "Tavily API Key 过长")
        String apiKey) {
}
