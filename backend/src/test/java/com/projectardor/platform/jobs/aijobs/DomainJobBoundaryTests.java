package com.projectardor.platform.jobs.aijobs;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Narrow executable architecture guard; does not claim the entire repository is decoupled. */
class DomainJobBoundaryTests {
    private static final Path ROOT=Path.of("src/main/java/com/projectardor");
    @Test void dispatchersDoNotImportTransportDtosOrReachDomainRepositories() throws Exception {
        for (String path:List.of("platform/jobs/aijobs/AiJobService.java", "platform/notification/BackgroundTaskService.java",
                "learning/service/LearningCreationJobHandler.java", "learning/service/LearningEvaluationJobHandler.java",
                "recap/service/ReplayEvaluationJobHandler.java", "interview/service/MockInterviewEvaluationJobHandler.java")) {
            String source=Files.readString(ROOT.resolve(path),StandardCharsets.UTF_8);
            assertThat(source).as(path).doesNotContain(".web.",".repository.");
        }
    }
    @Test void centralDispatchAndRetryContainNoTypeSwitchOrDefaultDomainFallback() throws Exception {
        for(String path:List.of("platform/jobs/aijobs/AiJobService.java", "platform/notification/BackgroundTaskService.java")) {
            String source=Files.readString(ROOT.resolve(path),StandardCharsets.UTF_8);
            assertThat(source).as(path).doesNotContain("switch (", "switch(", "default ->");
        }
    }
}
