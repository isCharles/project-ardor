package com.projectardor.admin.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.admin.web.AdminOverviewResponse;

@Service
public class AdminOverviewService {
    private static final Map<String, String> COUNT_TABLES = Map.ofEntries(
            Map.entry("users", "users"),
            Map.entry("conversations", "conversations"),
            Map.entry("messages", "messages"),
            Map.entry("resumes", "resumes"),
            Map.entry("recaps", "interview_recaps"),
            Map.entry("knowledgeDocuments", "knowledge_documents"),
            Map.entry("calendarTasks", "tasks"),
            Map.entry("memoryCards", "memory_cards"));

    private final JdbcTemplate jdbcTemplate;
    private final SystemApiConfigService configService;

    public AdminOverviewService(JdbcTemplate jdbcTemplate, SystemApiConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    @Transactional(readOnly = true)
    public AdminOverviewResponse get() {
        Map<String, Long> counts = new LinkedHashMap<>();
        COUNT_TABLES.forEach((name, table) -> counts.put(name,
                jdbcTemplate.queryForObject("select count(*) from " + table, Long.class)));
        return new AdminOverviewResponse(counts, true, configService.list());
    }
}
