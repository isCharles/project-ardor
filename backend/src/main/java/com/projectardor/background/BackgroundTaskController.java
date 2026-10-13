package com.projectardor.background;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import com.projectardor.auth.security.ArdorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
@Validated
@RequestMapping("/api/background-tasks")
public class BackgroundTaskController {
    private final BackgroundTaskStore store;
    private final BackgroundTaskService service;
    public BackgroundTaskController(BackgroundTaskStore store,BackgroundTaskService service) { this.store=store; this.service=service; }
    @GetMapping public List<BackgroundTaskStore.Task> list(@AuthenticationPrincipal ArdorPrincipal user,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue="30") @Min(1) @Max(100) int limit,
            @RequestParam(required=false) String kind,@RequestParam(required=false) UUID resourceId) {
        return store.list(user.userId(), offset, limit,kind,resourceId);
    }
    @GetMapping("/summary") public BackgroundTaskStore.Summary summary(@AuthenticationPrincipal ArdorPrincipal user) {
        return store.summary(user.userId());
    }
    @GetMapping("/{kind}/{id}") public BackgroundTaskStore.Task get(@AuthenticationPrincipal ArdorPrincipal user,
            @PathVariable String kind, @PathVariable UUID id) { return store.get(user.userId(),kind,id); }
    @PostMapping("/{kind}/{id}/read") public void read(@AuthenticationPrincipal ArdorPrincipal user,
            @PathVariable String kind, @PathVariable UUID id, @Valid @RequestBody ReadRequest input) {
        store.read(user.userId(),kind,id,input.completionToken());
    }
    public record ReadRequest(@NotBlank @Size(max=160) String completionToken) {}
    @PostMapping("/{kind}/{id}/retry")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public void retry(@AuthenticationPrincipal ArdorPrincipal user, @PathVariable String kind, @PathVariable UUID id,
            @Valid @RequestBody ReadRequest input) { service.retry(user.userId(),kind,id,input.completionToken()); }
}
