package com.projectardor.recap.web;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.recap.service.InterviewRecapService;
import jakarta.validation.Valid;
@RestController @RequestMapping("/api/memory-cards")
public class MemoryCardController {
    private final InterviewRecapService service;
    public MemoryCardController(InterviewRecapService service) { this.service = service; }
    @GetMapping public List<MemoryCardResponse> list(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam(defaultValue = "false") boolean dueOnly) { return service.listCards(principal.userId(), dueOnly).stream().map(MemoryCardResponse::from).toList(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public MemoryCardResponse create(@AuthenticationPrincipal ArdorPrincipal principal, @Valid @RequestBody MemoryCardCreateRequest request) {
        return MemoryCardResponse.from(service.createCard(principal.userId(), request.sourceType(), request.sourceLabel(),
                request.sourceUrl(), request.front(), request.back(), request.tags(), request.nextReviewAt(),
                request.recapQuestionId()));
    }
    @PutMapping("/{id}")
    public MemoryCardResponse update(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody MemoryCardUpdateRequest request) {
        return MemoryCardResponse.from(service.updateCard(principal.userId(), id,
                request.front(), request.back(), request.tags()));
    }
    @PostMapping("/{id}/reviews") public MemoryCardResponse review(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID id, @Valid @RequestBody MemoryCardReviewRequest request) { return MemoryCardResponse.from(service.review(principal.userId(), id, request.rating())); }
    @PostMapping("/{id}/suspend") public MemoryCardResponse suspend(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID id, @RequestParam(defaultValue = "true") boolean value) { return MemoryCardResponse.from(service.suspend(principal.userId(), id, value)); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) { service.deleteCard(principal.userId(), id); }
    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAll(@AuthenticationPrincipal ArdorPrincipal principal) { service.deleteAllCards(principal.userId()); }
}
