package com.projectardor.platform.jobs.aijobs;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

@RestController
@RequestMapping("/api/ai-jobs")
public class AiJobController {
    private final AiJobStore store;
    public AiJobController(AiJobStore store) { this.store = store; }

    @GetMapping public List<AiJobResponse> list(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam(required = false) AiJobType type, @RequestParam(required = false) UUID resourceId) {
        var samples = store.durationSamples();
        return store.list(principal.userId(), type, resourceId).stream().map(j -> AiJobResponse.from(j, -1, samples.get(j.type()))).toList();
    }
    @GetMapping("/{id}") public AiJobResponse get(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        var job = store.get(principal.userId(), id);
        int pos = "QUEUED".equals(job.status()) ? store.queuePosition(job) : -1;
        return AiJobResponse.from(job, pos, store.durationSamples().get(job.type()));
    }
    @PostMapping("/{id}/retry") public AiJobResponse retry(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        var job = store.retry(principal.userId(), id);
        return AiJobResponse.from(job, -1, store.durationSamples().get(job.type()));
    }
    @PostMapping("/{id}/cancel") public AiJobResponse cancel(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        return AiJobResponse.from(store.cancel(principal.userId(), id), -1, null);
    }
}
