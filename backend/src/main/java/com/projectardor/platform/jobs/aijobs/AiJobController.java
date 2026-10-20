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
        return store.list(principal.userId(), type, resourceId).stream().map(AiJobResponse::from).toList();
    }
    @GetMapping("/{id}") public AiJobResponse get(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        return AiJobResponse.from(store.get(principal.userId(), id));
    }
    @PostMapping("/{id}/retry") public AiJobResponse retry(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        return AiJobResponse.from(store.retry(principal.userId(), id));
    }
}
