package com.projectardor.learning.service;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.EnumSet;
import org.springframework.stereotype.Service;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.recap.service.InterviewRecordSourceAccess;
import com.projectardor.jobsearch.interview.service.InterviewContextAccess;

@Service
public class LearningContextResolver {
    private final InterviewRecordSourceAccess records;
    private final Map<LearningOriginKind,LearningOriginHandler> handlers;
    private final InterviewContextAccess interviews;
    public LearningContextResolver(List<LearningOriginHandler> registered, InterviewRecordSourceAccess records,
            InterviewContextAccess interviews) {
        this.records=records; this.interviews=interviews;
        var map=new EnumMap<LearningOriginKind,LearningOriginHandler>(LearningOriginKind.class);
        for(var handler:registered) {
            if(handler.kinds()==null || handler.kinds().isEmpty()) throw new IllegalStateException("Empty learning origin registration");
            for(var kind:handler.kinds()) {
                if(kind==null || kind==LearningOriginKind.LEGACY_RECAP || map.putIfAbsent(kind,handler)!=null)
                    throw new IllegalStateException("Duplicate or invalid learning origin handler");
            }
        }
        var required=EnumSet.allOf(LearningOriginKind.class);required.remove(LearningOriginKind.LEGACY_RECAP);
        if(!map.keySet().equals(required)) throw new IllegalStateException("Missing learning origin handler");
        handlers=Map.copyOf(map);
    }

    public Resolved resolve(UUID owner, LearningSourceType legacyType, UUID legacyId, LearningContextInput input) {
        validateAgreement(legacyType, legacyId, input);
        LearningOriginKind kind = input == null ? null : input.originKind();
        UUID id = input != null && input.originResourceId() != null ? input.originResourceId() : legacyId;
        if (kind == null && legacyType == LearningSourceType.RECAP) {
            var source = records.requireLegacy(owner, id);
            kind = LearningOriginKind.valueOf(source.kind().name());
        }
        if (kind == null) kind = LearningOriginKind.valueOf((legacyType == null ? LearningSourceType.MANUAL : legacyType).name());
        var handler=handlers.get(kind);
        if(handler==null) throw new IllegalArgumentException("请明确学习来源类型");
        var source=handler.resolve(owner,kind,id);
        UUID target = input == null ? null : input.targetInterviewId();
        UUID round = input == null ? null : input.targetRoundId();
        if (target != null || round != null) interviews.require(owner,target,round);
        return new Resolved(kind,source.resourceId(),source.recordId(),target,round);
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
        boolean autonomous = kind == LearningOriginKind.MANUAL || kind == LearningOriginKind.AGENT
                || kind == null && (legacyType == null || legacyType == LearningSourceType.MANUAL || legacyType == LearningSourceType.AGENT);
        if (autonomous && input.originResourceId() != null)
            throw new IllegalArgumentException("自主学习来源不接受资源 ID");
        if (input.targetRoundId() != null && input.targetInterviewId() == null)
            throw new IllegalArgumentException("准备目标轮次需要所属面试");
    }
    public record Resolved(LearningOriginKind kind, UUID resourceId, UUID recordId, UUID targetInterviewId, UUID targetRoundId) {}
}
