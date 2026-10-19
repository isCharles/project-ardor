package com.projectardor.recap.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.recap.domain.InterviewRecap;
import com.projectardor.recap.repository.InterviewRecapRepository;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;

/** Source identity and ancestry, not a Web DTO or a grant to read another user's text. */
@Service
public class InterviewRecordSourceAccess {
    private final InterviewRecapRepository records;
    private final InterviewRecapQuestionRepository questions;

    public InterviewRecordSourceAccess(InterviewRecapRepository records, InterviewRecapQuestionRepository questions) {
        this.records = records;
        this.questions = questions;
    }

    @Transactional(readOnly = true)
    public Source requireRecord(UUID owner, UUID id) {
        return source(Kind.INTERVIEW_RECORD, id, record(owner, id));
    }

    @Transactional(readOnly = true)
    public Source requireQuestion(UUID owner, UUID id) {
        if (id == null) throw missing();
        var question = questions.findByIdAndUserId(id, owner).orElseThrow(InterviewRecordSourceAccess::missing);
        return source(Kind.INTERVIEW_QUESTION, id, record(owner, question.getRecapId()));
    }

    /** Compatibility input must identify exactly one owned resource, never whichever table matched first. */
    @Transactional(readOnly = true)
    public Source requireLegacy(UUID owner, UUID id) {
        if (id == null) throw missing();
        var record = records.findByIdAndUserId(id, owner);
        var question = questions.findByIdAndUserId(id, owner);
        if (record.isPresent() && question.isPresent()) {
            throw new IllegalArgumentException("来源 ID 同时指向面试记录和问题，请明确来源类型");
        }
        if (record.isPresent()) return source(Kind.INTERVIEW_RECORD, id, record.get());
        if (question.isPresent()) return source(Kind.INTERVIEW_QUESTION, id, record(owner, question.get().getRecapId()));
        throw missing();
    }

    private InterviewRecap record(UUID owner, UUID id) {
        if (id == null) throw missing();
        return records.findByIdAndUserId(id, owner).orElseThrow(InterviewRecordSourceAccess::missing);
    }
    private static Source source(Kind kind, UUID id, InterviewRecap record) {
        return new Source(kind, id, record.getId(), record.getJobOpportunityId(), record.getInterviewRoundId());
    }
    private static ResourceNotFoundException missing() { return new ResourceNotFoundException("面试记录来源不存在"); }
    public enum Kind { INTERVIEW_RECORD, INTERVIEW_QUESTION }
    public record Source(Kind kind, UUID resourceId, UUID recordId, UUID sourceInterviewId, UUID sourceRoundId) {}
}
