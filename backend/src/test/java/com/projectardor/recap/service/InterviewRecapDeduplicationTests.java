package com.projectardor.recap.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.projectardor.recap.domain.QuestionPerformance;

class InterviewRecapDeduplicationTests {
    @Test void sameQuestionWithDifferentAnswersIsNotDiscarded() {
        var first=new InterviewRecapService.QuestionDraft("Explain Redis","First answer",List.of(),"Review",QuestionPerformance.UNKNOWN,null,null,List.of());
        var second=new InterviewRecapService.QuestionDraft("Explain Redis","Corrected answer after follow-up",List.of(),"Review",QuestionPerformance.UNKNOWN,null,null,List.of());
        assertThat(InterviewRecapService.uniqueQuestions(List.of(first,first,second))).containsExactly(first,second);
    }
}
