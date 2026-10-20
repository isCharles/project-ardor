package com.projectardor.learning.service;

import java.util.List;
import com.projectardor.recap.service.InterviewRecordSourceAccess;
import com.projectardor.recap.service.InterviewLearningOriginHandler;
import com.projectardor.resume.service.ResumeSourceAccess;
import com.projectardor.resume.service.ResumeLearningOriginHandler;
import com.projectardor.knowledge.service.KnowledgeSourceAccess;
import com.projectardor.knowledge.service.KnowledgeLearningOriginHandler;
import com.projectardor.opportunity.service.InterviewContextAccess;

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
