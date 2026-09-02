package com.projectardor.websearch.service;

import java.util.List;

public record WebSearchResult(
        String query,
        List<ResultItem> results,
        Integer creditsUsed,
        String responseTime) {

    public record ResultItem(String title, String url, String content, double score) {
    }
}
