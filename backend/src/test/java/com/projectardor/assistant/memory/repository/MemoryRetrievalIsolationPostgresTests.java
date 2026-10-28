package com.projectardor.assistant.memory.repository;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.projectardor.assistant.memory.repository.MemoryVectorStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real pgvector checks for the memory retrieval paths, in a random disposable schema: a search only
 * returns its own user's rows, an invalidated fact stops matching, and the core-memory column
 * defaults to unpinned so nothing becomes resident on its own.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class MemoryRetrievalIsolationPostgresTests {

    private JdbcTemplate root;
    private JdbcTemplate jdbc;
    private String schema;
    private final UUID owner = UUID.randomUUID();
    private final UUID foreign = UUID.randomUUID();
    private UUID ownerMemory;
    private UUID foreignMemory;

    @BeforeEach void prepare() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        schema = "ardor_memory_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        DataSource scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public", "ardor", "acceptance-only");
        Flyway.configure().dataSource(scoped).schemas(schema).defaultSchema(schema).target("80").load().migrate();
        jdbc = new JdbcTemplate(scoped);
        for (UUID user : List.of(owner, foreign)) {
            jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", user,
                    user + "@ardor.invalid", "synthetic-only");
        }
        ownerMemory = insert(owner, "Redis 限流用令牌桶", false);
        foreignMemory = insert(foreign, "Redis 限流用令牌桶", false);
    }

    private UUID insert(UUID user, String content, boolean pinned) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_agent_memory_items
                    (id, user_id, content, kind, confidence, importance, source_kind, valid_from,
                     pinned, embedding, embedding_dim, embedding_model)
                VALUES (?, ?, ?, 'fact', 1.0, 2, 'USER_EXPLICIT', CURRENT_TIMESTAMP, ?::boolean,
                        '[1,0]'::vector, 2, 'fixture')
                """, id, user, content, pinned);
        return id;
    }

    @AfterEach void cleanup() {
        if (root != null && schema != null) root.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void upgradeAddsTheCoreFlagWithoutPinningAnythingThatAlreadyExists() {
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history"
                + " WHERE success AND version IS NOT NULL", Integer.class)).isEqualTo(80);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_agent_memory_items WHERE pinned", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname = ?
                  AND indexname = 'ix_agent_memory_item_pinned'
                """, Integer.class, schema)).isEqualTo(1);
    }

    @Test void semanticMatchesAreLimitedToTheRequestingUserAndTheirOwnModel() {
        var store = new MemoryVectorStore(jdbc);
        assertThat(store.similar(owner, "[1,0]", 2, "fixture", 24))
                .extracting(MemoryVectorStore.Match::id).containsExactly(ownerMemory);
        assertThat(store.similar(foreign, "[1,0]", 2, "fixture", 24))
                .extracting(MemoryVectorStore.Match::id).containsExactly(foreignMemory);
        // Another embedding model or dimension must not borrow this user's vectors.
        assertThat(store.similar(owner, "[1,0]", 3, "fixture", 24)).isEmpty();
        assertThat(store.similar(owner, "[1,0]", 2, "other-model", 24)).isEmpty();
    }

    @Test void aCorrectedOrDeletedMemoryStopsMatchingRightAway() {
        var store = new MemoryVectorStore(jdbc);
        jdbc.update("UPDATE user_agent_memory_items SET valid_until = CURRENT_TIMESTAMP WHERE id = ?", ownerMemory);
        assertThat(store.similar(owner, "[1,0]", 2, "fixture", 24)).isEmpty();
        // The other user's identical memory is untouched by that invalidation.
        assertThat(store.similar(foreign, "[1,0]", 2, "fixture", 24))
                .extracting(MemoryVectorStore.Match::id).containsExactly(foreignMemory);

        jdbc.update("DELETE FROM user_agent_memory_items WHERE id = ?", foreignMemory);
        assertThat(store.similar(foreign, "[1,0]", 2, "fixture", 24)).isEmpty();
    }

    @Test void embeddingBackfillOnlySeesTheRequestingUser() {
        var store = new MemoryVectorStore(jdbc);
        jdbc.update("UPDATE user_agent_memory_items SET embedding = NULL, embedding_dim = NULL, embedding_model = NULL"
                + " WHERE user_id IN (?, ?)", owner, foreign);
        assertThat(store.pendingForUser(owner, "fixture", 10))
                .extracting(MemoryVectorStore.Pending::id).containsExactly(ownerMemory);
        assertThat(store.pendingForUser(foreign, "fixture", 10))
                .extracting(MemoryVectorStore.Pending::id).containsExactly(foreignMemory);
    }

    @Test void aPinnedCoreMemoryStaysInsideItsOwnerAndDisappearsWithTheRow() {
        var store = new MemoryVectorStore(jdbc);
        UUID core = insert(owner, "目标岗位：后端开发", true);
        assertThat(jdbc.queryForObject("SELECT pinned FROM user_agent_memory_items WHERE id = ?", Boolean.class, core))
                .isTrue();
        assertThat(store.similar(owner, "[1,0]", 2, "fixture", 24)).extracting(MemoryVectorStore.Match::id)
                .contains(core);
        jdbc.update("DELETE FROM users WHERE id = ?", owner);
        assertThat(store.similar(owner, "[1,0]", 2, "fixture", 24)).isEmpty();
    }
}
