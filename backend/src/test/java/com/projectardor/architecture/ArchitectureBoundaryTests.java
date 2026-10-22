package com.projectardor.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the domain boundaries that the architecture evolution work established.
 *
 * <p>These checks are source-level on purpose: they fail fast without a
 * database, run in every backend test pass, and describe the dependency
 * direction in the same terms as {@code docs/DOMAIN_CONTRACTS.md}. They do not
 * claim that every remaining cross-domain edge has been removed; additional
 * edges should be added here as their contracts are completed.</p>
 */
class ArchitectureBoundaryTests {
    @Test
    void mockInterviewApplicationAndToolsDoNotDependOnWebDtos() throws IOException {
        assertNoImports("learning/practice/mockinterview/service", Pattern.compile("import com\\.projectardor\\.learning\\.practice\\.mockinterview\\.web\\..*"));
        String tools = Files.readString(SOURCE_ROOT.resolve("assistant/tools/CareerAgentTools.java"), StandardCharsets.UTF_8);
        assertTrue(!tools.contains("com.projectardor.learning.practice.mockinterview.web."),
                "Mock-interview tools must use owned application views");
    }
    @Test
    void memoryAndApplicationActivityUseOwnedApplicationResults() throws IOException {
        assertNoImports("assistant/memory/service", Pattern.compile("import com\\.projectardor\\.assistant\\.memory\\.web\\..*"));
        assertNoImports("jobsearch/applicationactivity/service", Pattern.compile("import com\\.projectardor\\.jobsearch\\.applicationactivity\\.web\\..*"));
        String tools = Files.readString(SOURCE_ROOT.resolve("assistant/tools/CareerAgentTools.java"), StandardCharsets.UTF_8);
        assertTrue(!tools.contains("com.projectardor.assistant.memory.web.")
                        && !tools.contains("com.projectardor.jobsearch.applicationactivity.web."),
                "Memory and application activity tools must use owned views");
    }

    @Test
    void calendarApplicationAndToolsDoNotDependOnWebDtos() throws IOException {
        assertNoImports("scheduling/service", Pattern.compile("import com\\.projectardor\\.scheduling\\.web\\..*"));
        String tools = Files.readString(SOURCE_ROOT.resolve("assistant/tools/CareerAgentTools.java"), StandardCharsets.UTF_8);
        assertTrue(!tools.contains("com.projectardor.scheduling.web."),
                "Calendar tools must use scheduling application views");
    }

    @Test
    void learningApplicationAndAgentToolsDoNotDependOnLearningWebDtos() throws IOException {
        assertNoImports("learning/plan/service", Pattern.compile("import com\\.projectardor\\.learning\\.plan\\.web\\..*"));
        String tools = Files.readString(SOURCE_ROOT.resolve("assistant/tools/CareerAgentTools.java"), StandardCharsets.UTF_8);
        assertTrue(!tools.contains("com.projectardor.learning.plan.web"), "Agent learning tools must use owned application views");
    }

    private static final Path SOURCE_ROOT = resolveSourceRoot();

    @Test
    void jobSearchInterviewServicesUseApplicationViewsInsteadOfOwnWebDtos() throws IOException {
        assertNoImports(
                "jobsearch/interview/service",
                Pattern.compile("import com\\.projectardor\\.jobsearch\\.interview\\.web\\..*"));
    }

    @Test
    void learningReviewDoesNotReachIntoInterviewRecordStorage() throws IOException {
        assertNoImports(
                "learning/review",
                Pattern.compile("import com\\.projectardor\\.(jobsearch\\.record|learning\\.practice\\.question)\\.(domain|repository)\\..*"));
    }

    @Test
    void interviewRecordTransportDoesNotReachIntoLearningReviewRepositories() throws IOException {
        Pattern learningReviewRepository =
                Pattern.compile("import com\\.projectardor\\.learning\\.review\\.repository\\..*");
        assertNoImports("jobsearch/record", learningReviewRepository);
        assertNoImports("learning/practice/question", learningReviewRepository);
    }

    @Test
    void modelPlatformDoesNotImportAssistantOrBusinessDomains() throws IOException {
        assertNoImports(
                "platform/model/llm",
                Pattern.compile("import com\\.projectardor\\.(agent|assistant)\\.(service|tools|domain|repository|web)\\..*"));
    }

    @Test
    void platformJobKernelDoesNotImportBusinessWebDtos() throws IOException {
        Path jobs = SOURCE_ROOT.resolve("platform/jobs/aijobs");
        if (!Files.isDirectory(jobs)) {
            return;
        }
        Pattern businessWebImport = Pattern.compile("import com\\.projectardor\\.(?!common\\.)[a-z]+\\.web\\..*");
        List<String> violations;
        try (Stream<Path> files = Files.walk(jobs)) {
            violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(ArchitectureBoundaryTests::importLines)
                    .filter(line -> businessWebImport.matcher(line).matches())
                    .toList();
        }
        assertTrue(violations.isEmpty(), () -> "aijobs must not import business web DTOs: " + violations);
    }

    @Test
    void targetPackageStructureExistsAndLegacyBusinessPackagesAreGone() {
        for (String target : List.of(
                "jobsearch/applicationactivity", "jobsearch/interview", "jobsearch/record",
                "learning/plan", "learning/practice/exercises", "learning/practice/mockinterview",
                "learning/practice/question", "learning/review", "learning/progress",
                "materials/resume", "materials/knowledge", "assistant/conversation",
                "assistant/execution", "assistant/tools", "assistant/proposal", "assistant/memory",
                "scheduling", "platform/identity", "platform/model", "platform/jobs",
                "platform/usage", "platform/notification")) {
            Path packageDir = SOURCE_ROOT.resolve(target.replace('/', java.io.File.separatorChar));
            assertTrue(Files.isDirectory(packageDir), () -> "Missing target package: " + packageDir);
        }
        for (String legacy : List.of(
                "agent", "applications", "auth", "calendar", "interview", "knowledge",
                "opportunity", "profile", "recap", "resume", "system", "llm", "speech",
                "websearch", "integrations", "aijobs", "background", "usage")) {
            Path packageDir = SOURCE_ROOT.resolve(legacy);
            assertTrue(!Files.exists(packageDir), () -> "Legacy business package still exists: " + packageDir);
        }
    }

    private static Path resolveSourceRoot() {
        Path fromBackend = Path.of("src", "main", "java", "com", "projectardor");
        if (Files.isDirectory(fromBackend)) {
            return fromBackend;
        }
        return Path.of("backend", "src", "main", "java", "com", "projectardor");
    }

    private static void assertNoImports(String relativePackage, Pattern forbidden) throws IOException {
        Path packageDir = SOURCE_ROOT.resolve(relativePackage.replace('/', java.io.File.separatorChar));
        assertTrue(Files.isDirectory(packageDir), () -> "Missing package directory: " + packageDir);
        List<String> violations;
        try (Stream<Path> files = Files.walk(packageDir)) {
            violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(ArchitectureBoundaryTests::importLines)
                    .filter(line -> forbidden.matcher(line).matches())
                    .toList();
        }
        assertTrue(
                violations.isEmpty(),
                () -> relativePackage + " has forbidden imports: " + violations);
    }

    private static Stream<String> importLines(Path file) {
        try {
            List<String> imports = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ")) {
                    imports.add(trimmed);
                }
            }
            return imports.stream();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + file, exception);
        }
    }
}
