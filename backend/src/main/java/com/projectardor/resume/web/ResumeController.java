package com.projectardor.resume.web;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.resume.domain.ResumeAnalysis;
import com.projectardor.resume.service.ResumeService;
import com.projectardor.resume.service.ResumeAnalysisQueueService;
import com.projectardor.usage.QuotaProtected;
import com.projectardor.usage.UsageFeature;

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
            @PathVariable UUID resumeId) {
        return ResumeAnalysisTaskResponse.from(queueService.request(principal.userId(), resumeId));
    }

    @GetMapping("/{resumeId}/analysis/status")
    public ResumeAnalysisTaskResponse analysisStatus(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID resumeId) {
        return ResumeAnalysisTaskResponse.from(queueService.status(principal.userId(), resumeId));
    }

    @GetMapping("/{resumeId}/analysis")
    public ResumeAnalysisResponse getAnalysis(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID resumeId) {
        return ResumeAnalysisResponse.from(resumeService.getAnalysis(principal.userId(), resumeId));
    }

    @DeleteMapping("/{resumeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID resumeId) {
        resumeService.delete(principal.userId(), resumeId);
    }
}
