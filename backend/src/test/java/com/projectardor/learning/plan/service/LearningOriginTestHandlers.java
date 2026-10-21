package com.projectardor.learning.plan.service;

import java.util.List;
import com.projectardor.jobsearch.record.service.InterviewRecordSourceAccess;
import com.projectardor.learning.plan.origin.InterviewLearningOriginHandler;
import com.projectardor.materials.resume.service.ResumeSourceAccess;
import com.projectardor.materials.resume.service.ResumeLearningOriginHandler;
import com.projectardor.materials.knowledge.service.KnowledgeSourceAccess;
import com.projectardor.materials.knowledge.service.KnowledgeLearningOriginHandler;
import com.projectardor.jobsearch.interview.service.InterviewContextAccess;

final class LearningOriginTestHandlers {
    private LearningOriginTestHandlers() {}
    static List<LearningOriginHandler> handlers(InterviewRecordSourceAccess records, ResumeSourceAccess resumes, KnowledgeSourceAccess knowledge) {
        return List.of(new InterviewLearningOriginHandler(records),new ResumeLearningOriginHandler(resumes),
                new KnowledgeLearningOriginHandler(knowledge),new AutonomousLearningOriginHandler());
    }
    static LearningContextResolver resolver(InterviewRecordSourceAccess records, ResumeSourceAccess resumes,
            KnowledgeSourceAccess knowledge, InterviewContextAccess interviews) {
        return new LearningContextResolver(handlers(records,resumes,knowledge),records,interviews);
    }
}
