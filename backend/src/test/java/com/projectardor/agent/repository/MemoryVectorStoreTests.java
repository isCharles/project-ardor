package com.projectardor.agent.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class MemoryVectorStoreTests {
    @Test void maintenancePagesAreStableAndAdvancePastTheLastOwner() {
        var jdbc = mock(JdbcTemplate.class);
        UUID cursor = UUID.randomUUID();
        new MemoryVectorStore(jdbc).usersWithMemoriesAfter(cursor, 50);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(UUID.class), eq(cursor), eq(cursor), eq(50));
        assertThat(sql.getValue()).contains("ORDER BY user_id", "user_id > ?::uuid", "LIMIT ?");
    }
    @Test void staleEmbeddingWriteIsBoundToTheOriginalContentAndOwner() {
        var jdbc = mock(JdbcTemplate.class);
        UUID owner = UUID.randomUUID(), item = UUID.randomUUID();
        new MemoryVectorStore(jdbc).saveEmbedding(owner, item, "Original", "[1,0]", 2, "fixture");
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("[1,0]"), eq(2), eq("fixture"), eq(item), eq(owner), eq("Original"));
        assertThat(sql.getValue()).contains("content = ?", "user_id = ?", "valid_until IS NULL");
    }

    @Test void pendingUsersIncludeAdministratorDefaultsWithoutAPersonalConfig() {
        var jdbc = mock(JdbcTemplate.class);
        new MemoryVectorStore(jdbc).usersWithStaleEmbeddings(20);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(UUID.class), eq(20));
        assertThat(sql.getValue()).contains("LEFT JOIN auxiliary_api_configs", "system_api_configs", "COALESCE(c.model, s.model)");
    }
}
