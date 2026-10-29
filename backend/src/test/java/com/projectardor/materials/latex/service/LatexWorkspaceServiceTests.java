package com.projectardor.materials.latex.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
import com.projectardor.materials.resume.domain.Resume;
import com.projectardor.materials.resume.repository.ResumeRepository;

class LatexWorkspaceServiceTests {

    private final LatexProjectRepository projects = mock(LatexProjectRepository.class);
    private final LatexRevisionRepository revisions = mock(LatexRevisionRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final LatexWorkspaceService service = new LatexWorkspaceService(projects, revisions, resumes);

    private LatexRevision revision(LatexProject project, UUID userId, int number) {
        return LatexRevision.create(project.getId(), userId, number, Map.of("main.tex", "x"),
                LatexWorkspaceService.sha(Map.of("main.tex", "x")), null);
    }

    private LatexProject ownedProject(UUID userId, int currentRevision) {
        LatexProject project = LatexProject.create(userId, "我的简历", LatexWorkspaceService.DEFAULT_MAIN_FILE);
        project.advanceTo(currentRevision);
        when(projects.findByIdAndUserId(project.getId(), userId)).thenReturn(Optional.of(project));
        when(projects.findByIdAndUserIdForUpdate(project.getId(), userId)).thenReturn(Optional.of(project));
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
    void freezingLinksTheUploadedResumeAndANewVersionDropsIt() {
        UUID userId = UUID.randomUUID();
        LatexProject project = ownedProject(userId, 3);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(resumes.findByIdAndUserId(first, userId)).thenReturn(Optional.of(mock(Resume.class)));
        when(resumes.findByIdAndUserId(second, userId)).thenReturn(Optional.of(mock(Resume.class)));
        when(revisions.findByProjectIdAndUserIdAndRevisionNo(project.getId(), userId, 2))
                .thenReturn(Optional.of(revision(project, userId, 2)));
        when(revisions.findByProjectIdAndUserIdAndRevisionNo(project.getId(), userId, 3))
                .thenReturn(Optional.of(revision(project, userId, 3)));

        assertThat(service.freeze(userId, project.getId(), 2, first).frozenResumeId()).isEqualTo(first);
        assertThat(service.freeze(userId, project.getId(), 3).frozenResumeId())
                .as("a new frozen version has no PDF until the new one is uploaded")
                .isNull();
        assertThat(service.linkFrozenResume(userId, project.getId(), 3, second).frozenResumeId()).isEqualTo(second);
    }

    @Test
    void aFrozenResumeMustBelongToTheUserAndMatchTheFrozenRevision() {
        UUID userId = UUID.randomUUID();
        LatexProject project = ownedProject(userId, 3);
        UUID owned = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        when(resumes.findByIdAndUserId(owned, userId)).thenReturn(Optional.of(mock(Resume.class)));
        when(resumes.findByIdAndUserId(foreign, userId)).thenReturn(Optional.empty());
        when(revisions.findByProjectIdAndUserIdAndRevisionNo(project.getId(), userId, 2))
                .thenReturn(Optional.of(revision(project, userId, 2)));

        assertThatThrownBy(() -> service.freeze(userId, project.getId(), 2, foreign))
                .isInstanceOf(ResourceNotFoundException.class);
        project.freeze(2);
        assertThatThrownBy(() -> service.linkFrozenResume(userId, project.getId(), 1, owned))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.linkFrozenResume(userId, project.getId(), 2, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.linkFrozenResume(userId, project.getId(), 2, owned).frozenResumeId()).isEqualTo(owned);
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
    void archivingHidesTheWorkspaceAndIsIdempotent() {
        UUID userId = UUID.randomUUID();
        LatexProject project = ownedProject(userId, 2);

        service.archive(userId, project.getId());
        service.archive(userId, project.getId());

        assertThat(project.isArchived()).isTrue();
        assertThatThrownBy(() -> service.read(userId, project.getId(), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void restoringBringsAnArchivedWorkspaceBackAndIsIdempotent() {
        UUID userId = UUID.randomUUID();
        LatexProject project = ownedProject(userId, 2);
        service.archive(userId, project.getId());

        service.restore(userId, project.getId());
        service.restore(userId, project.getId());

        assertThat(project.isArchived()).isFalse();
        assertThat(service.rename(userId, project.getId(), "恢复后的简历").title()).isEqualTo("恢复后的简历");
    }

    @Test
    void archivedListUsesTheArchivedFinder() {
        UUID userId = UUID.randomUUID();
        LatexProject project = ownedProject(userId, 2);
        project.archive();
        when(projects.findByUserIdAndArchivedAtIsNotNullOrderByUpdatedAtDesc(userId))
                .thenReturn(List.of(project));

        assertThat(service.archived(userId)).extracting(LatexWorkspaceService.ProjectView::id)
                .containsExactly(project.getId());

        verify(projects).findByUserIdAndArchivedAtIsNotNullOrderByUpdatedAtDesc(userId);
    }

    @Test
    void everyWorkspaceMutationTakesTheRowLock() {
        UUID userId = UUID.randomUUID();
        LatexProject project = ownedProject(userId, 2);
        when(revisions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var revision = LatexRevision.create(project.getId(), userId, 2, Map.of("main.tex", "x"),
                LatexWorkspaceService.sha(Map.of("main.tex", "x")), null);
        when(revisions.findByProjectIdAndUserIdAndRevisionNo(project.getId(), userId, 2)).thenReturn(Optional.of(revision));

        service.save(userId, project.getId(), 2, Map.of("main.tex", "new"), null);
        service.freeze(userId, project.getId(), 2);
        service.rename(userId, project.getId(), "改名");
        service.archive(userId, project.getId());
        service.restore(userId, project.getId());

        verify(projects, times(5)).findByIdAndUserIdForUpdate(project.getId(), userId);
        verify(projects, never()).findByIdAndUserId(project.getId(), userId);
    }

    @Test
    void listOnlyAsksForActiveWorkspaces() {
        UUID userId = UUID.randomUUID();
        when(projects.findByUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(userId)).thenReturn(List.of());

        assertThat(service.list(userId)).isEmpty();

        verify(projects).findByUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(userId);
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
