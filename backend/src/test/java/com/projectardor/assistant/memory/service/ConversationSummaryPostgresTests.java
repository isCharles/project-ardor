package com.projectardor.assistant.memory.service;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.projectardor.common.web.ResourceNotFoundException;

/** Full V61 -> V62/V63 -> V64 upgrade, only inside a random disposable schema. */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class ConversationSummaryPostgresTests {
    private JdbcTemplate root, jdbc;
    private String schema;
    private DriverManagerDataSource source;
    private final UUID owner = UUID.randomUUID(), foreign = UUID.randomUUID();
    private final UUID conversation = UUID.randomUUID(), otherConversation = UUID.randomUUID();
    private final UUID message = UUID.randomUUID(), otherMessage = UUID.randomUUID();
    private ConversationSummaryStore summaries;

    @BeforeEach void prepare() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        schema = "ardor_summary_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                "ardor", "acceptance-only");
        migrate("61");
        jdbc = new JdbcTemplate(source);
        for (UUID user : new UUID[]{owner, foreign}) jdbc.update(
                "INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", user, user + "@ardor.invalid", "synthetic-only");
        jdbc.update("INSERT INTO conversations(id,user_id,title) VALUES(?,?,?)", conversation, owner, "synthetic conversation");
        jdbc.update("INSERT INTO conversations(id,user_id,title) VALUES(?,?,?)", otherConversation, foreign, "synthetic foreign");
        jdbc.update("INSERT INTO messages(id,user_id,conversation_id,role,content) VALUES(?,?,?,'USER',?)",
                message, owner, conversation, "keep the completed plan");
        jdbc.update("INSERT INTO messages(id,user_id,conversation_id,role,content) VALUES(?,?,?,'USER',?)",
                otherMessage, foreign, otherConversation, "synthetic other message");
        migrate("63");
        summaries = new ConversationSummaryStore(jdbc);
        summaries.save(owner, conversation, 1, message, 1, "synthetic summary", 10, "fixture-model");
        migrate("64");
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target(target).load().migrate();
    }

    @AfterEach void cleanup() {
        if (root != null && schema != null) root.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void populatedUpgradePreservesMessagesAndSummariesAndScopesReads() {
        assertThat(summaries.latest(owner, conversation)).get().extracting(ConversationSummaryStore.Summary::content)
                .isEqualTo("synthetic summary");
        assertThat(summaries.latest(foreign, conversation)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT content FROM messages WHERE id=?", String.class, message))
                .isEqualTo("keep the completed plan");
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class))
                .isEqualTo(64);
    }

    @Test void rejectsForeignConversationAndCoverageFromAnotherConversation() {
        assertThatThrownBy(() -> summaries.save(foreign, conversation, 2, message, 2, "bad", 1, "fixture"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> summaries.save(owner, conversation, 2, otherMessage, 2, "bad", 1, "fixture"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_summaries", Integer.class)).isEqualTo(1);
    }

    @Test void databaseRejectsWrongSummaryOwnerAndConversationRemovalKeepsExistingCascadeSemantics() {
        assertThatThrownBy(() -> jdbc.update("UPDATE conversation_summaries SET user_id=? WHERE conversation_id=?", foreign, conversation))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM conversations WHERE id=? AND user_id=?", conversation, owner);
        assertThat(summaries.latest(owner, conversation)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE id=?", Integer.class, otherMessage)).isEqualTo(1);
    }
}
