package com.projectardor.materials.latex.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.materials.latex.domain.LatexProject;
import com.projectardor.materials.latex.domain.LatexRevision;
import com.projectardor.materials.latex.repository.LatexProjectRepository;
import com.projectardor.materials.latex.repository.LatexRevisionRepository;

class LatexWorkspaceServiceTests {

    private final LatexProjectRepository projects = mock(LatexProjectRepository.class);
    private final LatexRevisionRepository revisions = mock(LatexRevisionRepository.class);
    private final LatexWorkspaceService service = new LatexWorkspaceService(projects, revisions);

    private LatexProject ownedProject(UUID userId, int currentRevision) {
        LatexProject project = LatexProject.create(userId, "我的简历", LatexWorkspaceService.DEFAULT_MAIN_FILE);
        project.advanceTo(currentRevision);
        when(projects.findByIdAndUserId(project.getId(), userId)).thenReturn(Optional.of(project));
        return project;
    }

    @Test
    void sanitizeRejectsPathsThatCouldEscapeTheCompileDirectory() {
        for (String path : new String[] {"../main.tex", "/etc/main.tex", "src/../../main.tex", "C:/main.tex", ".hidden.tex"}) {
            assertThatThrownBy(() -> LatexWorkspaceService.sanitize(Map.of(path, "x")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> LatexWorkspaceService.sanitize(Map.of("main.md", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LatexWorkspaceService.sanitize(Map.of("sections/intro.tex", "x")))
                .as("a workspace without main.tex cannot be compiled")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sanitizeKeepsNestedTextFilesAndRejectsBinarySuffixes() {
        Map<String, String> clean = LatexWorkspaceService.sanitize(
                Map.of("main.tex", "\\documentclass{article}", "styles/resume.cls", "x"));
        assertThat(clean).containsOnlyKeys("main.tex", "styles/resume.cls");
        assertThatThrownBy(() -> LatexWorkspaceService.sanitize(Map.of("main.tex", "x", "photo.png", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStaleBaseRevisionIsAConflictInsteadOfAnOverwrite() {
        UUID userId = UUID.randomUUID();
        LatexProject project = ownedProject(userId, 3);

        assertThatThrownBy(() -> service.save(userId, project.getId(), 2, Map.of("main.tex", "new"), "改一下"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
        verify(revisions, never()).saveAndFlush(any());
    }

    @Test
    void savingAppendsTheNextRevisionAndKeepsTheOldOne() {
        UUID userId = UUID.randomUUID();
        LatexProject project = ownedProject(userId, 2);
        when(revisions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var saved = service.save(userId, project.getId(), 2, Map.of("main.tex", "改过的内容"), "润色");

        assertThat(saved.revisionNo()).isEqualTo(3);
        assertThat(saved.files()).containsEntry("main.tex", "改过的内容");
        assertThat(project.getCurrentRevision()).isEqualTo(3);
        verify(revisions).saveAndFlush(any(LatexRevision.class));
    }

    @Test
    void freezingTwiceIsTheSameFreezeAndAMissingRevisionIsNotFound() {
        UUID userId = UUID.randomUUID();
        LatexProject project = ownedProject(userId, 2);
        var revision = LatexRevision.create(project.getId(), userId, 2, Map.of("main.tex", "x"),
                LatexWorkspaceService.sha(Map.of("main.tex", "x")), null);
        when(revisions.findByProjectIdAndUserIdAndRevisionNo(project.getId(), userId, 2)).thenReturn(Optional.of(revision));

        assertThat(service.freeze(userId, project.getId(), 2).frozenRevision()).isEqualTo(2);
        assertThat(service.freeze(userId, project.getId(), 2).frozenRevision()).isEqualTo(2);

        when(revisions.findByProjectIdAndUserIdAndRevisionNo(eq(project.getId()), eq(userId), eq(7)))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.freeze(userId, project.getId(), 7))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createSeedsTheTemplateAsRevisionOne() {
        UUID userId = UUID.randomUUID();
        when(projects.save(any())).thenAnswer(call -> call.getArgument(0));
        when(revisions.save(any())).thenAnswer(call -> call.getArgument(0));

        var created = service.create(userId, null, null, null);

        assertThat(created.revisionNo()).isEqualTo(1);
        assertThat(created.mainFile()).isEqualTo(LatexWorkspaceService.DEFAULT_MAIN_FILE);
        assertThat(created.files()).containsKeys(
                LatexWorkspaceService.DEFAULT_MAIN_FILE, "resume.cls", "linespacing_fix.sty", "zh_CN-Noto.sty");
        assertThat(created.files().get(LatexWorkspaceService.DEFAULT_MAIN_FILE)).contains("\\documentclass{resume}");
        assertThat(created.files().get("resume.cls")).contains("[\\titlerule]");
        assertThat(created.files().get("resume.cls")).doesNotContain("\\vspace{-0.5em}\\rule");
    }

    @Test
    void templateKeepsTheSourceClassLayoutWithoutNegativeSectionSpacing() {
        Map<String, String> template = LatexWorkspaceService.template();

        assertThat(template.get("resume.cls")).contains("\\ProvidesClass{resume}", "[\\titlerule]",
                "\\titlespacing*{\\section}{0cm}{*1}{*1}");
        assertThat(template.get("resume.cls")).doesNotContain("\\vspace{-0.5em}\\rule");
        assertThat(template.get(LatexWorkspaceService.DEFAULT_MAIN_FILE))
                .contains("示例姓名", "you@example.com", "138-0000-0000", "\\section{荣誉与奖项}");
    }

    @Test
    void anotherUsersProjectIsNotFoundRatherThanForbidden() {
        UUID userId = UUID.randomUUID();
        UUID foreignProject = UUID.randomUUID();
        when(projects.findByIdAndUserId(foreignProject, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read(userId, foreignProject, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
