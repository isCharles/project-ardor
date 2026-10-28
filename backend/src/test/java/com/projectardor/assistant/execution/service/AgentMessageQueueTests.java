package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.conversation.web.AgentMessageRequest;
import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.platform.usage.MembershipTier;

/**
 * Real PostgreSQL queue/steering races, isolated to connection-local tables. The steering stop is
 * triggered by a callback, so this test proves it runs outside the queue acceptance transaction.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AgentMessageQueueTests {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private AgentMessageQueue queue;
    private final tools.jackson.databind.ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    private final AgentConversationStore conversations = mock(AgentConversationStore.class);
    private final UUID owner = UUID.randomUUID();
    private final UUID conversation = UUID.randomUUID();
    private final UUID replacedRun = UUID.randomUUID();

    @BeforeEach
    void prepare() throws Exception {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:5432/ardor");
        // Dedicated acceptance credentials only. Do not consume arbitrary application credentials.
        var connection = DriverManager.getConnection(url, "ardor", "acceptance-only");
        dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        queue = new AgentMessageQueue(jdbc, mapper, conversations,
                new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE TEMP TABLE users(id uuid PRIMARY KEY)");
        jdbc.execute("""
                CREATE TEMP TABLE agent_runs (
                  id uuid PRIMARY KEY,
                  user_id uuid NOT NULL,
                  conversation_id uuid NOT NULL,
                  status varchar(24) NOT NULL,
                  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TEMP TABLE agent_message_queue (
                  id uuid PRIMARY KEY,
                  user_id uuid NOT NULL,
                  conversation_id uuid NOT NULL,
                  sequence BIGSERIAL NOT NULL UNIQUE,
                  payload jsonb NOT NULL,
                  status varchar(24) NOT NULL DEFAULT 'QUEUED',
                  blocked_by uuid,
                  error_message varchar(400),
                  priority smallint NOT NULL DEFAULT 0,
                  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.update("INSERT INTO users(id) VALUES(?)", owner);
        jdbc.update("INSERT INTO agent_runs(id, user_id, conversation_id, status) VALUES(?, ?, ?, 'RUNNING')",
                replacedRun, owner, conversation);
    }

    @AfterEach
    void close() {
        if (dataSource != null) dataSource.destroy();
    }

    private AgentMessageRequest request(UUID requestId) {
        return new AgentMessageRequest(conversation, "改成第二个方案", null, null, List.of(), requestId,
                ReasoningDepth.AUTO);
    }

    private String queueStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM agent_message_queue WHERE id = ?", String.class, id);
    }

    private String replacedRunStatus() {
        return jdbc.queryForObject("SELECT status FROM agent_runs WHERE id = ?", String.class, replacedRun);
    }

    @Test
    void stopFailureKeepsTheNewMessageQueuedWithoutFencingTheOldRun() {
        UUID requestId = UUID.randomUUID();

        var item = queue.enqueueAndReplace(owner, request(requestId),
                queued -> { throw new IllegalStateException("synthetic stop failure"); });

        assertThat(item.id()).isEqualTo(requestId);
        assertThat(item.status()).isEqualTo("QUEUED");
        assertThat(queueStatus(requestId)).isEqualTo("QUEUED");
        assertThat(replacedRunStatus()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT blocked_by FROM agent_message_queue WHERE id = ?",
                UUID.class, requestId)).isNull();
        assertThat(jdbc.queryForObject("SELECT sequence FROM agent_message_queue WHERE id = ?",
                Long.class, requestId)).isNegative();
        // claimNext already refuses to dispatch while the replaced run is RUNNING.
        assertThat(queue.claimNext()).isEmpty();
    }

    @Test
    void repeatedSteerOfTheSameRequestIdDoesNotStopAgain() {
        UUID requestId = UUID.randomUUID();
        AtomicInteger stops = new AtomicInteger();
        java.util.function.Consumer<AgentMessageQueue.Item> stop = queued -> {
            stops.incrementAndGet();
            throw new IllegalStateException("synthetic stop failure");
        };

        queue.enqueueAndReplace(owner, request(requestId), stop);
        var retried = queue.enqueueAndReplace(owner, request(requestId), stop);

        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(stops).hasValue(1);
        assertThat(queueStatus(requestId)).isEqualTo("QUEUED");
    }

    @Test
    void queuedSteeringMessageDispatchesOnlyAfterTheReplacedRunEnds() {
        UUID requestId = UUID.randomUUID();

        queue.enqueueAndReplace(owner, request(requestId), queued -> { /* cannot stop; run stays RUNNING */ });
        assertThat(queue.claimNext()).isEmpty();

        jdbc.update("UPDATE agent_runs SET status='COMPLETED' WHERE id=?", replacedRun);
        var claimed = queue.claimNext();

        assertThat(claimed).isPresent();
        assertThat(claimed.get().id()).isEqualTo(requestId);
        assertThat(claimed.get().status()).isEqualTo("DISPATCHING");
    }

    @Test
    void successfulStopLeavesOneQueuedMessageWithStableIdentity() {
        UUID requestId = UUID.randomUUID();

        var item = queue.enqueueAndReplace(owner, request(requestId),
                queued -> jdbc.update("UPDATE agent_runs SET status='FAILED' WHERE id=?", replacedRun));

        assertThat(item.status()).isEqualTo("QUEUED");
        assertThat(replacedRunStatus()).isEqualTo("FAILED");
        var claimed = queue.claimNext();
        assertThat(claimed).isPresent();
        assertThat(claimed.get().id()).isEqualTo(requestId);
    }

    private AgentMessageQueue queueWith(java.util.function.Function<UUID, MembershipTier> tiers) {
        return new AgentMessageQueue(jdbc, mapper, conversations,
                new DataSourceTransactionManager(dataSource), tiers, 60);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES(?)", id);
        return id;
    }

    @Test
    void listReportsQueueOrderWithinTheUsersQueuedMessages() {
        var first = queue.enqueue(owner, request(UUID.randomUUID()));
        var second = queue.enqueue(owner, request(UUID.randomUUID()));

        assertThat(queue.list(owner, conversation))
                .extracting(AgentMessageQueue.Item::id, AgentMessageQueue.Item::queuePosition)
                .containsExactly(tuple(first.id(), 1), tuple(second.id(), 2));
    }

    @Test
    void paidMessageStartsAheadOfAnOlderFreeMessage() {
        UUID freeUser = user();
        UUID paidUser = user();
        var priorityQueue = queueWith(id -> id.equals(paidUser) ? MembershipTier.PLUS : MembershipTier.FREE);

        var free = priorityQueue.enqueue(freeUser, request(UUID.randomUUID()));
        var paid = priorityQueue.enqueue(paidUser, request(UUID.randomUUID()));

        assertThat(jdbc.queryForObject("SELECT priority FROM agent_message_queue WHERE id=?",
                Integer.class, free.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT priority FROM agent_message_queue WHERE id=?",
                Integer.class, paid.id())).isEqualTo(1);
        assertThat(priorityQueue.claimNext()).get()
                .extracting(AgentMessageQueue.Item::id).isEqualTo(paid.id());
        assertThat(priorityQueue.claimNext()).get()
                .extracting(AgentMessageQueue.Item::id).isEqualTo(free.id());
    }

    @Test
    void oneAgingWindowLetsTheFreeMessageOvertakeFreshPaidArrivals() {
        UUID freeUser = user();
        UUID paidUser = user();
        var priorityQueue = queueWith(id -> id.equals(paidUser) ? MembershipTier.PLUS : MembershipTier.FREE);

        var free = priorityQueue.enqueue(freeUser, request(UUID.randomUUID()));
        jdbc.update("UPDATE agent_message_queue SET created_at=CURRENT_TIMESTAMP-INTERVAL '61 seconds' WHERE id=?",
                free.id());
        priorityQueue.enqueue(paidUser, request(UUID.randomUUID()));

        assertThat(priorityQueue.claimNext()).get()
                .extracting(AgentMessageQueue.Item::id).isEqualTo(free.id());
    }
}
