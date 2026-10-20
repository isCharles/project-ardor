package com.projectardor.platform.notification;

import java.util.Set;
import java.util.UUID;

/** Domain-owned retry; the coordinator has already checked owner, failed state and completion token. */
public interface BackgroundRetryHandler {
    Set<String> kinds();
    void retry(UUID owner, BackgroundTaskStore.Task task);
}
