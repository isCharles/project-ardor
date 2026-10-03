package com.projectardor.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class InterviewCodeRunnerTests {
    @Test
    void disabledRunnerNeverExecutesCode() {
        var runner = new InterviewCodeRunner("");
        assertThat(runner.available()).isFalse();
        assertThatThrownBy(() -> runner.runJava("class Main {}", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendsJavaWithBoundedExecutionAndReturnsCompilerOutput() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner.example/api/v2/execute"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"language\":\"java\""),
                        org.hamcrest.Matchers.containsString("\"compile_timeout\":10000"),
                        org.hamcrest.Matchers.containsString("\"run_timeout\":3000"),
                        org.hamcrest.Matchers.containsString("4 9\\n2 7 11 15"))))
                .andRespond(withSuccess("{\"compile\":{\"stdout\":\"\",\"stderr\":\"compile error\",\"code\":1},\"run\":null}",
                        MediaType.APPLICATION_JSON));
        var runner = new InterviewCodeRunner("http://runner.example", builder);
        assertThat(runner.available()).isTrue();
        var result = runner.runJava("public class Main {}", "4 9\n2 7 11 15");
        server.verify();
        assertThat(result.compileStderr()).isEqualTo("compile error");
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void rejectsOversizedInputBeforeContactingRunner() {
        var runner = new InterviewCodeRunner("http://runner.example");
        assertThatThrownBy(() -> runner.runJava("a".repeat(20_001), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.runJava("class Main {}", "x".repeat(8_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
