package com.projectardor.learning.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.recap.service.InterviewRecordSourceAccess;
import com.projectardor.resume.service.ResumeSourceAccess;
import com.projectardor.knowledge.service.KnowledgeSourceAccess;
import com.projectardor.opportunity.service.InterviewContextAccess;

@Service
public class LearningContextResolver {
    private final InterviewRecordSourceAccess records;
    private final ResumeSourceAccess resumes;
    private final KnowledgeSourceAccess knowledge;
    private final InterviewContextAccess interviews;
    public LearningContextResolver(InterviewRecordSourceAccess records, ResumeSourceAccess resumes,
            KnowledgeSourceAccess knowledge, InterviewContextAccess interviews) {
        this.records=records; this.resumes=resumes; this.knowledge=knowledge; this.interviews=interviews;
    }

    public Resolved resolve(UUID owner, LearningSourceType legacyType, UUID legacyId, LearningContextInput input) {
        validateAgreement(legacyType, legacyId, input);
        LearningOriginKind kind = input == null ? null : input.originKind();
        UUID id = input != null && input.originResourceId() != null ? input.originResourceId() : legacyId;
        UUID recordId = null;
        if (kind == null && legacyType == LearningSourceType.RECAP) {
            var source = records.requireLegacy(owner, id);
            kind = LearningOriginKind.valueOf(source.kind().name()); recordId = source.recordId();
        }
        if (kind == null) kind = LearningOriginKind.valueOf((legacyType == null ? LearningSourceType.MANUAL : legacyType).name());
        switch (kind) {
            case INTERVIEW_RECORD -> { var source = records.requireRecord(owner,id); recordId = source.recordId(); }
            case INTERVIEW_QUESTION -> { var source = records.requireQuestion(owner,id); recordId = source.recordId(); }
            case RESUME -> resumes.require(owner,id);
            case KNOWLEDGE -> knowledge.require(owner,id);
            case MANUAL, AGENT -> {
                if (input != null && input.originResourceId() != null) throw new IllegalArgumentException("自主学习来源不接受资源 ID");
                id = null; // Keep any legacy sourceId only in its compatibility field, not as a fabricated canonical reference.
            }
            case LEGACY_RECAP -> throw new IllegalArgumentException("请明确面试记录或问题来源");
        }
        UUID target = input == null ? null : input.targetInterviewId();
        UUID round = input == null ? null : input.targetRoundId();
        if (target != null || round != null) interviews.require(owner,target,round);
        return new Resolved(kind,id,recordId,target,round);
    }

    /** Pure request checks precede idempotent lookup; retries need not re-read a deleted source. */
    public static void validateAgreement(LearningSourceType legacyType, UUID legacyId, LearningContextInput input) {
        if (input == null) return;
        if (input.originResourceId() != null && legacyId != null && !input.originResourceId().equals(legacyId))
            throw new IllegalArgumentException("来源 ID 不一致");
        LearningOriginKind kind = input.originKind();
        if (kind == LearningOriginKind.LEGACY_RECAP) throw new IllegalArgumentException("历史未知来源不能用于新建计划");
        if (kind != null && legacyType != null && kind.legacyType() != legacyType)
            throw new IllegalArgumentException("来源类型不一致");
        if ((kind == LearningOriginKind.MANUAL || kind == LearningOriginKind.AGENT) && input.originResourceId() != null)
            throw new IllegalArgumentException("自主学习来源不接受资源 ID");
        if (input.targetRoundId() != null && input.targetInterviewId() == null)
            throw new IllegalArgumentException("准备目标轮次需要所属面试");
    }
    public record Resolved(LearningOriginKind kind, UUID resourceId, UUID recordId, UUID targetInterviewId, UUID targetRoundId) {}
}
