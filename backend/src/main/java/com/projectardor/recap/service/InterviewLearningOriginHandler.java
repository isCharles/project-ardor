package com.projectardor.recap.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.service.LearningOriginHandler;

@Component
public class InterviewLearningOriginHandler implements LearningOriginHandler {
    private final InterviewRecordSourceAccess records;
    public InterviewLearningOriginHandler(InterviewRecordSourceAccess records) { this.records=records; }
    public Set<LearningOriginKind> kinds() { return Set.of(LearningOriginKind.INTERVIEW_RECORD,LearningOriginKind.INTERVIEW_QUESTION); }
    public Reference resolve(UUID owner, LearningOriginKind kind, UUID resourceId) {
        var source=switch(kind) {
            case INTERVIEW_RECORD -> records.requireRecord(owner,resourceId);
            case INTERVIEW_QUESTION -> records.requireQuestion(owner,resourceId);
            default -> throw new IllegalArgumentException("不是面试记录来源");
        };
        return new Reference(source.resourceId(),source.recordId());
    }
}
