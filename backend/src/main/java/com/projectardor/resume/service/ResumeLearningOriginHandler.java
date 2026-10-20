package com.projectardor.resume.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.service.LearningOriginHandler;

@Component
public class ResumeLearningOriginHandler implements LearningOriginHandler {
    private final ResumeSourceAccess resumes;
    public ResumeLearningOriginHandler(ResumeSourceAccess resumes) { this.resumes=resumes; }
    public Set<LearningOriginKind> kinds() { return Set.of(LearningOriginKind.RESUME); }
    public Reference resolve(UUID owner, LearningOriginKind kind, UUID resourceId) {
        resumes.require(owner,resourceId);
        return new Reference(resourceId,null);
    }
}
