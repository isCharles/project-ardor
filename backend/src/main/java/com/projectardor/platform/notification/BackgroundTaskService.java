package com.projectardor.platform.notification;

import java.util.UUID;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BackgroundTaskService {
    private final BackgroundTaskStore store;
    private final Map<String,BackgroundRetryHandler> handlers;
    public BackgroundTaskService(BackgroundTaskStore store, List<BackgroundRetryHandler> registered) {
        this.store=store;
        var map=new HashMap<String,BackgroundRetryHandler>();
        for (var handler:registered) {
            if (handler.kinds() == null || handler.kinds().isEmpty()) throw new IllegalStateException("Empty background retry registration");
            for (String kind:handler.kinds()) {
                if (kind == null || kind.isBlank() || map.putIfAbsent(kind,handler) != null)
                    throw new IllegalStateException("Duplicate or invalid background retry handler");
            }
        }
        handlers=Map.copyOf(map);
    }
    public void retry(UUID userId, String kind, UUID id, String token) {
        var task = store.get(userId,kind,id);
        if (!"FAILED".equals(task.status())) throw new IllegalStateException("只有失败任务可以重试");
        var handler=handlers.get(task.kind());
        if (handler == null) throw new IllegalArgumentException("此任务类型不支持重试");
        store.read(userId,kind,id,token);
        handler.retry(userId,task);
    }
}
