package com.projectardor.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MemoryExecutorTests {
    @Test void callerBackpressureCannotJoinAnAlreadyCommittedEventTransaction() throws Exception {
        var annotation = com.projectardor.agent.service.MemoryConsolidationService.class
                .getMethod("consolidate", com.projectardor.agent.service.MemoryConsolidationRequested.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(annotation.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED);
    }
    @Test void saturatedMemoryQueueDoesNotSilentlyDiscardWork() throws Exception {
        var executor = new ConversationTitleConfig().memoryConsolidationExecutor();
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(3);
        try {
            executor.execute(() -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } completed.countDown(); });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            executor.execute(completed::countDown);
            executor.execute(completed::countDown);
            release.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); executor.shutdown(); }
    }
}
