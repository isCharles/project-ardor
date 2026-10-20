package com.projectardor.materials.knowledge.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.background.BackgroundRetryHandler;
import com.projectardor.background.BackgroundTaskStore;

@Component
public class KnowledgeIndexRetryHandler implements BackgroundRetryHandler {
    private final KnowledgeIndexTaskStore tasks;
    private final KnowledgeIndexDispatcher dispatcher;
    public KnowledgeIndexRetryHandler(KnowledgeIndexTaskStore tasks, KnowledgeIndexDispatcher dispatcher) {
        this.tasks=tasks; this.dispatcher=dispatcher;
    }
    public Set<String> kinds() { return Set.of("KNOWLEDGE_INDEX"); }
    public void retry(UUID owner, BackgroundTaskStore.Task task) {
        tasks.retry(owner,task.id());
        dispatcher.dispatchPending(owner);
    }
}
