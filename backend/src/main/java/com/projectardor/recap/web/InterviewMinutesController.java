package com.projectardor.recap.web;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.recap.service.InterviewMinutesService;
import com.projectardor.platform.usage.UsageFeature;

/** Confirmation is intentionally not exposed as an Agent tool. */
@RestController
@RequestMapping("/api/interview-recaps/drafts")
public class InterviewMinutesController {
    private final InterviewMinutesService minutes;
    private final com.projectardor.recap.service.InterviewRecapQueueService queue;
    private final com.projectardor.platform.usage.QuotaService quotas;
    public InterviewMinutesController(InterviewMinutesService minutes,com.projectardor.recap.service.InterviewRecapQueueService queue,
            com.projectardor.platform.usage.QuotaService quotas) {
        this.minutes=minutes; this.queue=queue; this.quotas=quotas;
    }
    @PostMapping("/from-report/{recapId}")
    public java.util.Map<String,UUID> fromReport(@AuthenticationPrincipal ArdorPrincipal p,@PathVariable UUID recapId,
            @RequestBody(required=false) SourceRequest request) {
        UUID source=minutes.sourceDraft(p.userId(),recapId);
        if (source == null) {
            quotas.consume(p.userId(),UsageFeature.INTERVIEW_RECAP,UUID.nameUUIDFromBytes(("legacy-minutes:"+recapId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            source=queue.fromReport(p.userId(),recapId,request==null ? null : request.jobOpportunityId()).jobId();
        }
        return java.util.Map.of("draftId",source);
    }
    @GetMapping public List<InterviewMinutesService.DraftSummary> list(@AuthenticationPrincipal ArdorPrincipal p) { return minutes.list(p.userId()); }
    @GetMapping("/{id}") public InterviewMinutesService.Draft get(@AuthenticationPrincipal ArdorPrincipal p,@PathVariable UUID id) { return minutes.get(p.userId(),id); }
    @PutMapping("/{id}") public InterviewMinutesService.Draft save(@AuthenticationPrincipal ArdorPrincipal p,@PathVariable UUID id,@Valid @RequestBody Edit r) {
        return minutes.save(p.userId(),id,r.revision(),r.minutes());
    }
    @PostMapping("/{id}/confirm")
    public InterviewMinutesService.Draft confirm(@AuthenticationPrincipal ArdorPrincipal p,@PathVariable UUID id,@Valid @RequestBody Confirm r) {
        return minutes.confirm(p.userId(),id,r.revision());
    }
    @PostMapping("/{id}/reviews/{reviewId}/retry")
    public InterviewMinutesService.Draft retry(@AuthenticationPrincipal ArdorPrincipal p,@PathVariable UUID id,@PathVariable UUID reviewId) {
        return minutes.retryReview(p.userId(),id,reviewId);
    }
    public record Edit(@Min(1) long revision,@NotBlank @Size(min=30,max=50000) String minutes) {}
    public record Confirm(@Min(1) long revision,@NotNull UUID requestId) {}
    public record SourceRequest(UUID jobOpportunityId) {}
}
