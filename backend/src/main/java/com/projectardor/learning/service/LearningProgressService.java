package com.projectardor.learning.service;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.learning.repository.LearningPlanRepository;
import com.projectardor.learning.repository.LearningAttemptRepository;
import com.projectardor.learning.practice.question.service.InterviewQuestionProgressAccess;
import com.projectardor.learning.practice.question.service.QuestionPracticeProgressAccess;
import com.projectardor.learning.review.service.FlashcardProgressAccess;
import com.projectardor.jobsearch.record.service.InterviewRecordSourceAccess;
import com.projectardor.jobsearch.interview.service.InterviewContextAccess;
import com.projectardor.common.web.ResourceNotFoundException;

/** Read-only learning query. Source ownership and practice facts remain with their domains. */
@Service
public class LearningProgressService {
    private final InterviewQuestionProgressAccess questions;
    private final QuestionPracticeProgressAccess practice;
    private final FlashcardProgressAccess review;
    private final LearningPlanRepository plans;
    private final LearningAttemptRepository attempts;
    private final InterviewRecordSourceAccess legacySources;
    private final InterviewContextAccess interviews;
    public LearningProgressService(InterviewQuestionProgressAccess questions, QuestionPracticeProgressAccess practice,FlashcardProgressAccess review,
            LearningPlanRepository plans, LearningAttemptRepository attempts,
            InterviewRecordSourceAccess legacySources, InterviewContextAccess interviews) {
        this.questions=questions;this.practice=practice;this.review=review;this.plans=plans;this.attempts=attempts;
        this.legacySources=legacySources;this.interviews=interviews;
    }
    @Transactional(readOnly=true)
    public View view(UUID owner, UUID sourceInterviewId, int page, int size) {
        if(page<0 || size<1 || size>50 || page>Integer.MAX_VALUE/size)
            throw new IllegalArgumentException("分页参数无效，每页最多 50 项");
        if(sourceInterviewId!=null) interviews.require(owner,sourceInterviewId,null);
        var window=questions.questions(owner,sourceInterviewId,page,size);
        var questionIds=new LinkedHashSet<UUID>();var sourceIds=new LinkedHashSet<UUID>();
        for(var question:window.questions()) { questionIds.add(question.id());sourceIds.add(question.id());sourceIds.add(question.recapId()); }
        var snapshot=practice.snapshot(owner,questionIds);
        var cardSnapshot=review.snapshot(owner,questionIds);
        var exact=new LinkedHashMap<UUID,List<Plan>>();var broad=new LinkedHashMap<UUID,List<Plan>>();
        int unresolvedSources=0;
        if(!sourceIds.isEmpty()) for(var plan:plans.progressPlans(owner,sourceIds)) {
            var kind=plan.getOriginKind();UUID source=plan.getOriginResourceId(),record=plan.getOriginRecordId();
            if(kind==null && plan.getSourceType()==LearningSourceType.RECAP && plan.getSourceId()!=null) {
                try {
                    var legacy=legacySources.requireLegacy(owner,plan.getSourceId());
                    kind=LearningOriginKind.valueOf(legacy.kind().name());source=legacy.resourceId();record=legacy.recordId();
                } catch(ResourceNotFoundException | IllegalArgumentException missingOrAmbiguous) { unresolvedSources++;continue; }
            }
            if(kind==LearningOriginKind.LEGACY_RECAP) { unresolvedSources++;continue; }
            if(kind!=LearningOriginKind.INTERVIEW_QUESTION && kind!=LearningOriginKind.INTERVIEW_RECORD) continue;
            if(source==null) { unresolvedSources++;continue; }
            var result=new Plan(plan.getId(),plan.getConcept(),plan.getStatus().name(),plan.getAttemptCount(),plan.getLastScore(),
                    plan.getSourceType().name(),plan.getSourceId(),kind.name(),source,record,plan.getTargetInterviewId(),plan.getTargetRoundId());
            var destination=kind==LearningOriginKind.INTERVIEW_QUESTION ? exact : broad;
            destination.computeIfAbsent(source,ignored -> new ArrayList<>()).add(result);
        }
        var tracks=window.questions().stream().map(question -> new Track(question,
                exact.getOrDefault(question.id(),List.of()),broad.getOrDefault(question.recapId(),List.of()),
                cardSnapshot.cards().getOrDefault(question.id(),List.of()),snapshot.replays().get(question.id()))).toList();
        return new View(page,size,window.total(),(long)page*size+tracks.size()<window.total(),sourceInterviewId,
                new AccountSummary(plans.countByUserId(owner),attempts.countByUserId(owner),cardSnapshot.totalCards(),snapshot.totalReplayAttempts()),
                unresolvedSources,tracks);
    }
    public record Plan(UUID id,String concept,String status,int attemptCount,Integer lastScore,String sourceType,UUID sourceId,
            String originKind,UUID originResourceId,UUID originRecordId,UUID targetInterviewId,UUID targetRoundId) {}
    /** Account counts remain visible even without a real interview; they are not mastery scores. */
    public record AccountSummary(long learningPlans,long recordedLearningAttempts,long flashcards,long replayAttempts) {}
    public record Track(InterviewQuestionProgressAccess.Question question,List<Plan> learningPlans,List<Plan> interviewLearningPlans,
            List<FlashcardProgressAccess.Card> cards,QuestionPracticeProgressAccess.Replay replay) {
        public Track { learningPlans=List.copyOf(learningPlans);interviewLearningPlans=List.copyOf(interviewLearningPlans);cards=List.copyOf(cards); }
    }
    public record View(int page,int size,long totalQuestions,boolean hasNext,UUID sourceInterviewId,
            AccountSummary accountSummary,int unresolvedSources,List<Track> tracks) { public View { tracks=List.copyOf(tracks); } }
}
