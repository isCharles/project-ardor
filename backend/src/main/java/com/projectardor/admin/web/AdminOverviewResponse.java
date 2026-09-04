package com.projectardor.admin.web;

import java.util.List;
import java.util.Map;

public record AdminOverviewResponse(
        Map<String, Long> counts,
        boolean databaseAvailable,
        List<AdminApiConfigResponse> apiConfigs) {
}
