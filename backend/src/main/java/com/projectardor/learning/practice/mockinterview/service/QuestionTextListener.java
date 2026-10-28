package com.projectardor.learning.practice.mockinterview.service;

import com.projectardor.learning.practice.mockinterview.service.QuestionTextStreamScanner.Fragment;

/** Called with question text while the model is still generating the document. */
@FunctionalInterface
public interface QuestionTextListener {

    QuestionTextListener IGNORE = fragment -> { };

    void onQuestionText(Fragment fragment);
}
