package com.projectardor.recap.web;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.recap.service.InterviewRecapService;
import com.projectardor.recap.service.InterviewRecapQueueService;
import jakarta.validation.Valid;
@RestController @RequestMapping("/api/interview-recaps")
public class InterviewRecapController {
    private final InterviewRecapService service;
    private final InterviewRecapQueueService queueService;
    public InterviewRecapController(InterviewRecapService service, InterviewRecapQueueService queueService) { this.service = service; this.queueService = queueService; }
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public InterviewRecapTaskResponse create(@AuthenticationPrincipal ArdorPrincipal principal, @Valid @RequestBody InterviewRecapCreateRequest request) {
        return InterviewRecapTaskResponse.from(queueService.request(principal.userId(), request.content()));
    }
    @GetMapping("/jobs") public List<InterviewRecapTaskResponse> jobs(@AuthenticationPrincipal ArdorPrincipal principal) { return queueService.list(principal.userId()).stream().map(InterviewRecapTaskResponse::from).toList(); }
    @GetMapping public List<InterviewRecapResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) { return service.list(principal.userId()); }
    @GetMapping("/{id}") public InterviewRecapResponse get(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) { return service.detail(principal.userId(), id); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) { service.delete(principal.userId(), id); }
}
