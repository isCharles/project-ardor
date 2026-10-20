package com.projectardor.aijobs;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Real PostgreSQL predicates, isolated to a connection-local table; no production rows are accessed. */
@EnabledIfEnvironmentVariable(named="ARDOR_ACCEPTANCE_DISPOSABLE_DB",matches="true")
class AiJobAttemptFencingTests {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private AiJobStore store;
    private final UUID id=UUID.randomUUID(),owner=UUID.randomUUID();
    private final Instant original=Instant.parse("2020-01-01T00:00:00Z");

    @BeforeEach void prepare() throws Exception {
        String url=System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL","jdbc:postgresql://127.0.0.1:5432/ardor");
        // Dedicated acceptance credentials only. Do not consume arbitrary application credentials.
        var connection=DriverManager.getConnection(url,"ardor","acceptance-only");
        dataSource=new SingleConnectionDataSource(connection,true);
        jdbc=new JdbcTemplate(dataSource);store=new AiJobStore(jdbc,Duration.ofMinutes(5));
        jdbc.execute("CREATE TEMP TABLE ai_jobs(id uuid,user_id uuid,status text,attempts integer,result_path text,error_code text,error_message text,finished_at timestamptz,updated_at timestamptz)");
        jdbc.update("INSERT INTO ai_jobs(id,user_id,status,attempts,updated_at) VALUES(?,?,'RUNNING',2,?)",id,owner,java.sql.Timestamp.from(original));
    }
    @AfterEach void close() { if(dataSource!=null) dataSource.destroy(); }
    private AiJobStore.Job attempt(int number) {
        return new AiJobStore.Job(id,owner,AiJobType.LEARNING_EVALUATE,UUID.randomUUID(),"fixture","{}",
                "RUNNING",number,null,null,null,original,original,null);
    }
    private String status() { return jdbc.queryForObject("SELECT status FROM ai_jobs",String.class); }

    @Test void lateCompletionCannotFinishANewerAttempt() {
        store.complete(attempt(1),"/app/learning?id=old");
        assertThat(status()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT result_path FROM ai_jobs",String.class)).isNull();
        store.complete(attempt(2),"/app/learning?id=current");
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT result_path FROM ai_jobs",String.class)).isEqualTo("/app/learning?id=current");
    }
    @Test void lateFailureCannotFailANewerAttemptButCurrentFailureStillWorks() {
        store.fail(attempt(1),"OLD_FAILURE","old");
        assertThat(status()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT error_code FROM ai_jobs",String.class)).isNull();
        store.fail(attempt(2),"CURRENT_FAILURE","current");
        assertThat(status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM ai_jobs",String.class)).isEqualTo("CURRENT_FAILURE");
    }
    @Test void staleHeartbeatCannotKeepANewerAttemptAlive() {
        store.heartbeat(attempt(1));
        assertThat(jdbc.queryForObject("SELECT updated_at FROM ai_jobs",java.sql.Timestamp.class).toInstant()).isEqualTo(original);
        store.heartbeat(attempt(2));
        assertThat(jdbc.queryForObject("SELECT updated_at FROM ai_jobs",java.sql.Timestamp.class).toInstant()).isAfter(original);
    }
}
