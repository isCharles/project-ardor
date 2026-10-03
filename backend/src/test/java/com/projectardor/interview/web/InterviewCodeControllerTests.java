package com.projectardor.interview.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.interview.domain.InterviewQuestion;
import com.projectardor.interview.domain.InterviewSession;
import com.projectardor.interview.service.InterviewCodeRunner;
import com.projectardor.interview.service.InterviewService;

class InterviewCodeControllerTests {
    @Test
    void onlyRunsCurrentCodingQuestionOwnedByPrincipal() {
        var interviews = mock(InterviewService.class);
        var runner = mock(InterviewCodeRunner.class);
        var controller = new InterviewController(interviews, runner);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var principal = new ArdorPrincipal(userId, "user@example.com", "", true, UserRole.USER);
        var session = InterviewSession.create(userId, null, null, "Java 开发");
        var current = InterviewQuestion.create(userId, sessionId, 1, "题目", "CODING", java.util.List.of());
        when(interviews.getNextQuestion(userId, sessionId))
                .thenReturn(new InterviewService.InterviewProgress(session, current, 0, 1));

        assertThatThrownBy(() -> controller.runCode(principal, sessionId,
                new RunCodeRequest(UUID.randomUUID(), "public class Main {}", "")))
                .isInstanceOf(IllegalStateException.class);
        verify(runner, never()).runJava(eq("public class Main {}"), eq(""));

        controller.runCode(principal, sessionId,
                new RunCodeRequest(current.getId(), "public class Main {}", ""));
        verify(runner).runJava("public class Main {}", "");
    }
}
