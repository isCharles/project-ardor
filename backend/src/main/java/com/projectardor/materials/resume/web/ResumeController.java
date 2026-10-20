package com.projectardor.materials.resume.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
import com.projectardor.materials.resume.domain.ResumeAnalysis;
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
        var resume = resumeService.upload(principal.userId(), file);
        return ResumeResponse.from(resume, null, null);
    }

    @GetMapping
    public List<ResumeResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        List<ResumeAnalysis> analyses = resumeService.listAnalyses(principal.userId());
        return resumeService.list(principal.userId()).stream()
                .map(resume -> ResumeResponse.from(
                        resume,
                        analyses.stream()
                                .filter(analysis -> analysis.getResumeId().equals(resume.getId()))
                                .findFirst()
                                .orElse(null),
                        queueService.statusIfPresent(principal.userId(), resume.getId())
                                .map(ResumeAnalysisTaskResponse::from)
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
                request == null ? "" : request.targetRole(), request == null ? "" : request.jobDescription()));
    }

    public record AnalysisRequest(String targetRole, String jobDescription) {}

    @GetMapping("/{resumeId}/analysis/status")
    public ResumeAnalysisTaskResponse analysisStatus(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID resumeId) {
        return ResumeAnalysisTaskResponse.from(queueService.status(principal.userId(), resumeId));
    }

    @GetMapping("/{resumeId}/analysis")
    public ResumeAnalysisResponse getAnalysis(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID resumeId,
            @RequestParam(required = false) UUID analysisId) {
        var report = analysisId == null ? resumeService.getAnalysis(principal.userId(), resumeId)
                : resumeService.getAnalysisById(principal.userId(), analysisId);
        if (!report.getResumeId().equals(resumeId)) throw new com.projectardor.common.web.ResourceNotFoundException("简历分析不存在");
        return ResumeAnalysisResponse.from(report);
    }

    @GetMapping("/{resumeId}/analysis/history")
    public List<ResumeAnalysisResponse> history(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID resumeId) {
        return resumeService.history(principal.userId(), resumeId).stream().map(ResumeAnalysisResponse::from).toList();
    }

    @DeleteMapping("/{resumeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID resumeId) {
        resumeService.delete(principal.userId(), resumeId);
    }
}
