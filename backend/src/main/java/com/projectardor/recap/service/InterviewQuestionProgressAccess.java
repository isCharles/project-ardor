package com.projectardor.recap.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import com.projectardor.recap.domain.QuestionPerformance;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;

/** Job-search-owned, paged question provenance. Does not expose the raw transcript/report. */
@Service
public class InterviewQuestionProgressAccess {
    private final InterviewRecapQuestionRepository questions;
    public InterviewQuestionProgressAccess(InterviewRecapQuestionRepository questions) { this.questions=questions; }
    @Transactional(readOnly=true)
    public Window questions(UUID owner, UUID sourceInterviewId, int page, int size) {
        var result=questions.progressQuestions(owner,List.of(QuestionPerformance.WEAK,QuestionPerformance.MIXED),
                sourceInterviewId,PageRequest.of(page,size));
        return new Window(result.getTotalElements(),result.getContent().stream().map(q -> new Question(q.getId(),q.getRecapId(),
                q.getQuestionText(),q.getObservation(),q.getPerformance().name(),q.getRecapTitle(),q.getRecapCreatedAt(),
                q.getInterviewId(),q.getRoundId())).toList());
    }
    public record Question(UUID id, UUID recapId, String questionText, String observation, String performance,
            String recapTitle, Instant recapCreatedAt, UUID interviewId, UUID roundId) {}
    public record Window(long total, List<Question> questions) { public Window { questions=List.copyOf(questions); } }
}
