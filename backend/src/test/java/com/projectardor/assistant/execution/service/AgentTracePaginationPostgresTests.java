package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.projectardor.common.web.ResourceNotFoundException;

@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AgentTracePaginationPostgresTests {
    @Test void hundredTurnsHaveNoGapsOrDuplicatesAndForeignCursorsCannotLeak() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        String schema = "ardor_trace_page_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public", "ardor", "acceptance-only");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), conversation = UUID.randomUUID(), foreignConversation = UUID.randomUUID(), ownOtherConversation = UUID.randomUUID();
            for (UUID user : List.of(owner, other)) jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", user, user + "@ardor.invalid", "synthetic-only");
            jdbc.update("INSERT INTO conversations(id,user_id) VALUES(?,?),(?,?),(?,?)", conversation, owner, foreignConversation, other, ownOtherConversation, owner);
            for (int i = 1; i <= 105; i++) jdbc.update("""
                    INSERT INTO agent_runs(id,user_id,conversation_id,request_hash,message,status,created_at)
                    VALUES(?,?,?,?,'Synthetic turn','COMPLETED','2026-01-01T00:00:00Z')
                    """, new UUID(0, i), owner, conversation, "x".repeat(64));
            UUID foreignRun = UUID.randomUUID(), ownOtherRun = UUID.randomUUID();
            jdbc.update("INSERT INTO agent_runs(id,user_id,conversation_id,request_hash,message) VALUES(?,?,?,?,'Private'),(?,?,?,?,'Other conversation')",
                    foreignRun, other, foreignConversation, "x".repeat(64), ownOtherRun, owner, ownOtherConversation, "x".repeat(64));
            var store = new AgentRunStore(jdbc, null, null);
            UUID cursor = null;
            var ids = new LinkedHashSet<UUID>();
            int pages = 0;
            do {
                var page = store.tracePage(owner, conversation, cursor, 30);
                assertThat(page.runs()).hasSizeLessThanOrEqualTo(30);
                assertThat(page.runs()).extracting(AgentRunStore.AgentRun::id).isSortedAccordingTo(Comparator.comparing(UUID::toString));
                for (var run : page.runs()) assertThat(ids.add(run.id())).isTrue();
                cursor = page.nextBefore(); pages++;
            } while (cursor != null);
            assertThat(pages).isEqualTo(4); assertThat(ids).hasSize(105);
            assertThat(store.tracePage(owner, conversation, null, Integer.MAX_VALUE).runs()).hasSize(100);
            assertThatThrownBy(() -> store.tracePage(owner, conversation, foreignRun, 30)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> store.tracePage(owner, conversation, ownOtherRun, 30)).isInstanceOf(ResourceNotFoundException.class);
            assertThat(store.tracePage(other, conversation, null, 30).runs()).isEmpty();
        } finally { root.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
}
