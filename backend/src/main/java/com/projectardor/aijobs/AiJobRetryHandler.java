package com.projectardor.aijobs;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import com.projectardor.background.BackgroundRetryHandler;
import com.projectardor.background.BackgroundTaskStore;

@Component
public class AiJobRetryHandler implements BackgroundRetryHandler {
    private final AiJobStore jobs;
    public AiJobRetryHandler(AiJobStore jobs) { this.jobs=jobs; }
    public Set<String> kinds() { return Arrays.stream(AiJobType.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet()); }
    public void retry(UUID owner, BackgroundTaskStore.Task task) { jobs.retry(owner,task.id()); }
}
