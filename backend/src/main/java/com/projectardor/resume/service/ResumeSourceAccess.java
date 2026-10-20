package com.projectardor.resume.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.resume.repository.ResumeRepository;

/** Validates an owned immutable upload identity without exposing extracted personal text. */
@Service
public class ResumeSourceAccess {
    private final ResumeRepository resumes;
    public ResumeSourceAccess(ResumeRepository resumes) { this.resumes = resumes; }
    public void require(UUID owner, UUID id) {
        if (id == null || resumes.findByIdAndUserId(id, owner).isEmpty()) throw new ResourceNotFoundException("简历来源不存在");
    }
}
