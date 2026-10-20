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

    private static final Path SOURCE_ROOT = resolveSourceRoot();

    @Test
    void opportunityServicesUseApplicationViewsInsteadOfOwnWebDtos() throws IOException {
        assertNoImports(
                "opportunity/service",
                Pattern.compile("import com\\.projectardor\\.opportunity\\.web\\..*"));
    }

    @Test
    void learningReviewDoesNotReachIntoInterviewRecordStorage() throws IOException {
        assertNoImports(
                "learning/review",
                Pattern.compile("import com\\.projectardor\\.recap\\.(domain|repository)\\..*"));
    }

    @Test
    void interviewRecordTransportDoesNotReachIntoLearningReviewRepositories() throws IOException {
        assertNoImports(
                "recap",
                Pattern.compile("import com\\.projectardor\\.learning\\.review\\.repository\\..*"));
    }

    @Test
    void modelPlatformDoesNotImportAssistantOrBusinessDomains() throws IOException {
        assertNoImports(
                "llm",
                Pattern.compile("import com\\.projectardor\\.(agent|assistant)\\.(service|tools|domain|repository|web)\\..*"));
    }

    @Test
    void platformJobKernelDoesNotImportBusinessWebDtos() throws IOException {
        Path jobs = SOURCE_ROOT.resolve("aijobs");
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
