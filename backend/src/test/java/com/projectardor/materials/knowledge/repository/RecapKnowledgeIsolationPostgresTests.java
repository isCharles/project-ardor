package com.projectardor.materials.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Recaps indexed as knowledge documents stay inside their own owner, checked on a real PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class RecapKnowledgeIsolationPostgresTests {

    @Test void recapChunksAreOnlyEverRecalledByTheirOwnOwner() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        String schema = "ardor_recap_rag_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(url + "?currentSchema=" + schema + ",public", "ardor", "acceptance-only");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            var jdbc = new JdbcTemplate(source);

            UUID owner = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            UUID ownerDoc = UUID.randomUUID();
            UUID otherDoc = UUID.randomUUID();
            for (UUID id : new UUID[]{owner, other}) {
                jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", id, id + "@ardor.invalid", "synthetic-only");
            }
            // Same embedding on both sides: a missing owner filter would return the other recap too.
            insertRecapDocument(jdbc, owner, ownerDoc, "A 厂一面：Redis 限流怎么防超卖");
            insertRecapDocument(jdbc, other, otherDoc, "B 厂一面：MySQL 事务隔离级别");

            var store = new KnowledgeVectorStore(jdbc);
            List<UUID> ownerDense = store.search(owner, "[1,0,0]", 3, 10).stream()
                    .map(KnowledgeVectorStore.VectorHit::documentId).toList();
            assertThat(ownerDense).containsExactly(ownerDoc);
            assertThat(store.search(other, "[1,0,0]", 3, 10).stream()
                    .map(KnowledgeVectorStore.VectorHit::documentId).toList()).containsExactly(otherDoc);

            List<UUID> ownerLexical = store.lexicalSearch(owner, List.of("限流", "超卖"), 10).stream()
                    .map(KnowledgeVectorStore.LexicalHit::documentId).toList();
            assertThat(ownerLexical).containsExactly(ownerDoc);
            assertThat(store.lexicalSearch(other, List.of("限流", "超卖"), 10))
                    .noneMatch(hit -> hit.documentId().equals(ownerDoc));

            // Deleting the recap's document must take its chunks with it, so no orphan stays searchable.
            jdbc.update("DELETE FROM knowledge_documents WHERE id = ? AND user_id = ?", ownerDoc, owner);
            assertThat(store.search(owner, "[1,0,0]", 3, 10)).isEmpty();
            assertThat(store.search(other, "[1,0,0]", 3, 10)).hasSize(1);
        } finally {
            root.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static void insertRecapDocument(JdbcTemplate jdbc, UUID userId, UUID documentId, String title) {
        jdbc.update("""
                INSERT INTO knowledge_documents(id, user_id, title, source_type, source_url, raw_content, created_at, updated_at)
                VALUES(?, ?, ?, 'RECAP', ?, ?, now(), now())
                """, documentId, userId, title, "ardor://interview-recaps/" + UUID.randomUUID(), "问题：怎么防止超卖？");
        jdbc.update("""
                INSERT INTO knowledge_chunks(id, user_id, document_id, sequence_number, content, embedding, embedding_dim)
                VALUES(?, ?, ?, 1, ?, '[1,0,0]'::vector, 3)
                """, UUID.randomUUID(), userId, documentId, "用数据库乐观锁版本号，配合 Redis + Lua 原子扣减。");
    }
}
