package com.projectardor.materials.resume.web;

import java.util.List;
import java.util.UUID;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.materials.resume.service.ResumeService;
import com.projectardor.materials.resume.service.ResumeAnalysisQueueService;
import com.projectardor.platform.usage.QuotaProtected;
import com.projectardor.platform.usage.UsageFeature;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumeService;
    private final ResumeAnalysisQueueService queueService;

    public ResumeController(ResumeService resumeService, ResumeAnalysisQueueService queueService) {
        this.resumeService = resumeService;
        this.queueService = queueService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResumeResponse upload(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        var resume = resumeService.uploadView(principal.userId(), file);
        return ResumeResponse.from(resume, null);
    }

    @GetMapping
    public List<ResumeResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return resumeService.listViews(principal.userId()).stream()
                .map(resume -> ResumeResponse.from(
                        resume,
                        queueService.statusIfPresent(principal.userId(), resume.id())
                                .orElse(null)))
                .toList();
    }

    @PostMapping("/{resumeId}/analysis")
    @QuotaProtected(UsageFeature.RESUME_ANALYSIS)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResumeAnalysisTaskResponse analyze(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID resumeId,
            @RequestBody(required = false) AnalysisRequest request) {
        return ResumeAnalysisTaskResponse.from(queueService.request(principal.userId(), resumeId,
                request == null ? "" : request.targetRole(),
                request == null ? "" : request.jobDescription(),
                request == null ? "" : request.roleFamily()));
    }

    public record AnalysisRequest(String targetRole, String jobDescription, String roleFamily) {}

    @GetMapping("/{resumeId}/analysis/status")
    public ResumeAnalysisTaskResponse analysisStatus(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID resumeId,
            @RequestParam(required = false) UUID jobId) {
        return ResumeAnalysisTaskResponse.from(queueService.status(principal.userId(), resumeId, jobId));
    }

    @GetMapping("/{resumeId}/analysis")
    public ResumeAnalysisResponse getAnalysis(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID resumeId,
            @RequestParam(required = false) UUID analysisId) {
        return ResumeAnalysisResponse.fromSnapshot(resumeService.report(principal.userId(), resumeId, analysisId));
    }

    @GetMapping("/{resumeId}/analysis/history")
    public List<ResumeAnalysisResponse> history(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID resumeId) {
        return resumeService.analysisHistory(principal.userId(), resumeId).stream().map(ResumeAnalysisResponse::fromSnapshot).toList();
    }

    @GetMapping("/{resumeId}/file")
    public ResponseEntity<byte[]> file(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID resumeId) {
        var file = resumeService.file(principal.userId(), resumeId);
        String filename = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + filename)
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content());
    }

    @GetMapping("/{resumeId}/preview/pages/{page}")
    public ResponseEntity<byte[]> previewPage(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID resumeId,
            @PathVariable int page) {
        var preview = resumeService.previewPage(principal.userId(), resumeId, page);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                .header("X-Resume-Page-Count", String.valueOf(preview.pageCount()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .body(preview.png());
    }

    @DeleteMapping("/{resumeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID resumeId) {
        resumeService.delete(principal.userId(), resumeId);
    }
}
