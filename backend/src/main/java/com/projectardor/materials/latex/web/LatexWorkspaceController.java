package com.projectardor.materials.latex.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.materials.latex.service.LatexWorkspaceService;
import com.projectardor.materials.latex.service.LatexWorkspaceService.ProjectView;
import com.projectardor.materials.latex.service.LatexWorkspaceService.RevisionView;
import com.projectardor.materials.latex.service.LatexWorkspaceService.Snapshot;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

@RestController
@RequestMapping("/api/latex/projects")
public class LatexWorkspaceController {

    private final LatexWorkspaceService service;

    public LatexWorkspaceController(LatexWorkspaceService service) {
        this.service = service;
    }

    public record CreateRequest(String title, Map<String, String> files, String message) {}

    public record SaveRequest(int baseRevision, Map<String, String> files, String message) {}

    public record FreezeRequest(int revisionNo) {}

    public record RenameRequest(String title) {}

    @GetMapping
    public List<ProjectView> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return service.list(principal.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Snapshot create(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestBody(required = false) CreateRequest request) {
        CreateRequest body = request == null ? new CreateRequest(null, null, null) : request;
        return service.create(principal.userId(), body.title(), body.files(), body.message());
    }

    @GetMapping("/{projectId}")
    public Snapshot read(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID projectId,
            @RequestParam(required = false) Integer revision) {
        return service.read(principal.userId(), projectId, revision);
    }

    @GetMapping("/{projectId}/revisions")
    public List<RevisionView> revisions(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID projectId) {
        return service.history(principal.userId(), projectId);
    }

    @PostMapping("/{projectId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public Snapshot save(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID projectId,
            @RequestBody SaveRequest request) {
        return service.save(principal.userId(), projectId, request.baseRevision(), request.files(), request.message());
    }

    @PostMapping("/{projectId}/freeze")
    public ProjectView freeze(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID projectId,
            @RequestBody FreezeRequest request) {
        return service.freeze(principal.userId(), projectId, request.revisionNo());
    }

    @PatchMapping("/{projectId}")
    public ProjectView rename(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID projectId,
            @RequestBody RenameRequest request) {
        return service.rename(principal.userId(), projectId, request == null ? null : request.title());
    }
}
