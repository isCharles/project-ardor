package com.projectardor.jobsearch.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.jobsearch.applicationactivity.service.ApplicationMetric;
import com.projectardor.jobsearch.applicationactivity.service.ApplicationRhythmService;
import com.projectardor.jobsearch.discovery.repository.JobDiscoveryRepository;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.platform.identity.profile.service.ProfileView;
import com.projectardor.platform.jobs.aijobs.AiJobStore;
import com.projectardor.platform.jobs.aijobs.AiJobType;
import com.projectardor.platform.model.websearch.service.TavilySearchService;
import com.projectardor.platform.model.websearch.service.WebSearchResult;
import com.projectardor.platform.usage.MembershipService;
import com.projectardor.platform.usage.MembershipTier;

import tools.jackson.databind.json.JsonMapper;

class JobDiscoveryServiceTests {

    private static final String LEVER_ID = "3f0c2b4e-6a1d-4f2b-9c3d-2a1b0c9d8e7f";
    private static final String JOB_URL = "https://jobs.lever.co/acme/" + LEVER_ID;
    private static final String PAGE = """
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"JobPosting","title":"Senior Backend Engineer",
             "hiringOrganization":{"name":"Acme"},
             "jobLocation":{"address":{"addressLocality":"Beijing"}}}
            </script>
            """;

    private final JobDiscoveryRepository repository = mock(JobDiscoveryRepository.class);
    private final TavilySearchService search = mock(TavilySearchService.class);
    private final SafePageFetcher fetcher = mock(SafePageFetcher.class);
    private final ApplicationRhythmService rhythm = mock(ApplicationRhythmService.class);
    private final ProfileService profiles = mock(ProfileService.class);
    private final AiJobStore jobs = mock(AiJobStore.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final JobDiscoveryService service = new JobDiscoveryService(repository, search, fetcher,
            new JobPageParser(), rhythm, profiles, jobs, memberships, JsonMapper.builder().build());

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void defaults() {
        when(memberships.tier(any())).thenReturn(MembershipTier.FREE);
        lenient().when(profiles.view(any())).thenReturn(new ProfileView("Owner", "", List.of("Backend Engineer"), "Asia/Shanghai"));
    }

    private static JobDiscoveryRepository.Run run(UUID id, UUID owner, String status, List<String> roles) {
        return new JobDiscoveryRepository.Run(id, owner, id, status, roles, List.of(), List.of(), List.of(),
                0, 0, 0, 0, 0, 0, "[]", false, Instant.now(), null, null);
    }

    private static JobDiscoveryRepository.PostingView view(UUID postingId, Instant appliedAt) {
        var posting = new JobDiscoveryRepository.Posting(postingId, "lever:" + LEVER_ID, "Acme",
                "Senior Backend Engineer", "Beijing", JOB_URL, "jobs.lever.co", null, null, null,
                "OPEN", Instant.now(), Instant.now());
        var discovery = new JobDiscoveryRepository.Discovery(UUID.randomUUID(), postingId, "NEW",
                "标题包含目标岗位「Backend Engineer」", "页面标题：Senior Backend Engineer",
                "Backend Engineer jobs", Instant.now(), Instant.now(), appliedAt);
        return new JobDiscoveryRepository.PostingView(posting, discovery, List.of());
    }

    @Test void startFallsBackToProfileRolesAndQueuesOnTheExistingWorker() {
        UUID requestId = UUID.randomUUID();
        var queued = run(requestId, user, "QUEUED", List.of("Backend Engineer"));
        when(repository.findRun(user, requestId)).thenReturn(Optional.empty(), Optional.of(queued));
        when(repository.findActiveRun(user)).thenReturn(Optional.empty());

        var result = service.start(user, requestId, new JobDiscoveryService.Criteria(null, List.of("Beijing"), null));

        assertThat(result.status()).isEqualTo("QUEUED");
        verify(repository).savePreferences(user, List.of("Backend Engineer"), List.of("Beijing"), List.of());
        verify(repository).insertRun(requestId, user, List.of("Backend Engineer"), List.of("Beijing"), List.of());
        verify(jobs).enqueue(user, requestId, AiJobType.JOB_DISCOVERY, requestId, "{}", 0);
    }

    @Test void startRefusesWhenNoReliableRoleExistsOrAnotherRunIsActive() {
        UUID requestId = UUID.randomUUID();
        when(profiles.view(user)).thenReturn(new ProfileView("Owner", "", List.of(), "Asia/Shanghai"));
        assertThatThrownBy(() -> service.start(user, requestId, new JobDiscoveryService.Criteria(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标岗位");
        verify(repository, never()).insertRun(any(), any(), any(), any(), any());

        when(profiles.view(user)).thenReturn(new ProfileView("Owner", "", List.of("Backend Engineer"), "Asia/Shanghai"));
        when(repository.findActiveRun(user)).thenReturn(Optional.of(run(UUID.randomUUID(), user, "RUNNING", List.of("Backend Engineer"))));
        assertThatThrownBy(() -> service.start(user, requestId, new JobDiscoveryService.Criteria(List.of("Backend Engineer"), null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void aSuccessfulRunSavesMatchedFactsAndNeverTouchesApplicationCounts() {
        UUID runId = UUID.randomUUID();
        var queued = run(runId, user, "QUEUED", List.of("Backend Engineer"));
        when(repository.findRun(user, runId)).thenReturn(Optional.of(queued));
        when(repository.cancelRequested(user, runId)).thenReturn(false);
        when(search.search(user, "Backend Engineer jobs", 6)).thenReturn(new WebSearchResult("q",
                List.of(new WebSearchResult.ResultItem("Senior Backend Engineer - Acme", JOB_URL,
                        "北京 后端 招聘", 0.9)), 1, "1s"));
        when(fetcher.fetch(JOB_URL)).thenReturn(new SafePageFetcher.Page(JOB_URL, 200, PAGE,
                SafePageFetcher.STATUS_OPEN, null));
        when(repository.findPosting("lever:" + LEVER_ID)).thenReturn(Optional.empty());
        when(repository.insertPosting(any())).thenAnswer(call -> call.getArgument(0));
        when(repository.insertDiscovery(any(), any(), any(), any(), any(), any())).thenReturn(true);

        String path = service.perform(user, runId);

        assertThat(path).isEqualTo("/app/job-search/discovery?run=" + runId);
        ArgumentCaptor<JobDiscoveryRepository.Posting> posting = ArgumentCaptor.forClass(JobDiscoveryRepository.Posting.class);
        verify(repository).insertPosting(posting.capture());
        assertThat(posting.getValue().company()).isEqualTo("Acme");
        assertThat(posting.getValue().title()).isEqualTo("Senior Backend Engineer");
        assertThat(posting.getValue().location()).isEqualTo("Beijing");
        assertThat(posting.getValue().linkStatus()).isEqualTo(SafePageFetcher.STATUS_OPEN);
        verify(repository).insertDiscovery(any(), eq(user), eq(posting.getValue().id()),
                contains("Backend Engineer"), contains("页面标题"), eq("Backend Engineer jobs"));
        verify(repository).finish(eq(user), eq(runId), eq("COMPLETED"), eq(1), eq(1), eq(1), eq(0), eq(0), eq(0), anyString());
        verify(repository, never()).updateState(any(), any(), any());
        verifyNoInteractions(rhythm);
    }

    @Test void rediscoveryRefreshesTheSamePostingWithoutResettingTheInboxState() {
        UUID runId = UUID.randomUUID();
        var postingId = UUID.randomUUID();
        var existing = new JobDiscoveryRepository.Posting(postingId, "lever:" + LEVER_ID, "Acme Old",
                "Backend Engineer", "Beijing", JOB_URL, "jobs.lever.co", null, null, null,
                "CLOSED", Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3600));
        when(repository.findRun(user, runId)).thenReturn(Optional.of(run(runId, user, "QUEUED", List.of("Backend Engineer"))));
        when(repository.cancelRequested(user, runId)).thenReturn(false);
        when(search.search(user, "Backend Engineer jobs", 6)).thenReturn(new WebSearchResult("q",
                List.of(new WebSearchResult.ResultItem("Senior Backend Engineer - Acme", JOB_URL, "北京", 0.9)), 1, "1s"));
        when(fetcher.fetch(JOB_URL)).thenReturn(new SafePageFetcher.Page(JOB_URL, 200, PAGE,
                SafePageFetcher.STATUS_OPEN, null));
        when(repository.findPosting("lever:" + LEVER_ID)).thenReturn(Optional.of(existing));
        when(repository.insertPosting(any())).thenReturn(existing);
        when(repository.insertDiscovery(any(), any(), any(), any(), any(), any())).thenReturn(false);

        service.perform(user, runId);

        ArgumentCaptor<JobDiscoveryRepository.Posting> refreshed = ArgumentCaptor.forClass(JobDiscoveryRepository.Posting.class);
        verify(repository).refreshPosting(refreshed.capture());
        assertThat(refreshed.getValue().id()).isEqualTo(postingId);
        assertThat(refreshed.getValue().linkStatus()).isEqualTo("OPEN");
        assertThat(refreshed.getValue().lastVerifiedAt()).isAfter(existing.lastVerifiedAt());
        assertThat(refreshed.getValue().firstSeenAt()).isEqualTo(existing.firstSeenAt());
        verify(repository).addSource(postingId, "jobs.lever.co", JOB_URL);
        verify(repository).touchDiscovery(eq(user), eq(postingId), anyString(), anyString(), eq("Backend Engineer jobs"));
        verify(repository).finish(eq(user), eq(runId), eq("COMPLETED"), eq(1), eq(1), eq(0), eq(1), eq(0), eq(0), anyString());
        verify(repository, never()).updateState(any(), any(), any());
    }

    @Test void anUnknownRefreshKeepsTheLastVerifiedLinkState() {
        UUID runId = UUID.randomUUID();
        var postingId = UUID.randomUUID();
        Instant verifiedHourAgo = Instant.now().minusSeconds(3600);
        var existing = new JobDiscoveryRepository.Posting(postingId, "lever:" + LEVER_ID, "Acme",
                "Backend Engineer", "Beijing", JOB_URL, "jobs.lever.co", null, null, null,
                "OPEN", verifiedHourAgo, verifiedHourAgo);
        when(repository.findRun(user, runId)).thenReturn(Optional.of(run(runId, user, "QUEUED", List.of("Backend Engineer"))));
        when(repository.cancelRequested(user, runId)).thenReturn(false);
        when(search.search(user, "Backend Engineer jobs", 6)).thenReturn(new WebSearchResult("q",
                List.of(new WebSearchResult.ResultItem("Senior Backend Engineer - Acme", JOB_URL, "北京", 0.9)), 1, "1s"));
        when(fetcher.fetch(JOB_URL)).thenReturn(new SafePageFetcher.Page(JOB_URL, 500, PAGE,
                SafePageFetcher.STATUS_UNKNOWN, "未能确认页面状态"));
        when(repository.findPosting("lever:" + LEVER_ID)).thenReturn(Optional.of(existing));
        when(repository.insertPosting(any())).thenAnswer(call -> call.getArgument(0));

        service.perform(user, runId);

        ArgumentCaptor<JobDiscoveryRepository.Posting> refreshed = ArgumentCaptor.forClass(JobDiscoveryRepository.Posting.class);
        verify(repository).refreshPosting(refreshed.capture());
        assertThat(refreshed.getValue().linkStatus()).isEqualTo("OPEN");
        assertThat(refreshed.getValue().lastVerifiedAt()).isEqualTo(verifiedHourAgo);
    }

    @Test void postingIdentityComesFromTheResolvedApplicationUrl() {
        UUID runId = UUID.randomUUID();
        String redirecting = "https://jobs.example.com/go/42";
        when(repository.findRun(user, runId)).thenReturn(Optional.of(run(runId, user, "QUEUED", List.of("Backend Engineer"))));
        when(repository.cancelRequested(user, runId)).thenReturn(false);
        when(search.search(user, "Backend Engineer jobs", 6)).thenReturn(new WebSearchResult("q",
                List.of(new WebSearchResult.ResultItem("Senior Backend Engineer - Acme", redirecting, "北京", 0.9)), 1, "1s"));
        when(fetcher.fetch(redirecting)).thenReturn(new SafePageFetcher.Page(JOB_URL, 200, PAGE,
                SafePageFetcher.STATUS_OPEN, null));
        when(repository.findPosting("lever:" + LEVER_ID)).thenReturn(Optional.empty());
        when(repository.insertPosting(any())).thenAnswer(call -> call.getArgument(0));
        when(repository.insertDiscovery(any(), any(), any(), any(), any(), any())).thenReturn(true);

        service.perform(user, runId);

        ArgumentCaptor<JobDiscoveryRepository.Posting> saved = ArgumentCaptor.forClass(JobDiscoveryRepository.Posting.class);
        verify(repository).insertPosting(saved.capture());
        assertThat(saved.getValue().canonicalKey()).isEqualTo("lever:" + LEVER_ID);
        assertThat(saved.getValue().applyUrl()).isEqualTo(JOB_URL);
        verify(repository).addSource(any(), eq("jobs.lever.co"), eq(redirecting));
    }

    @Test void aCandidateStorageFailureIsRecordedWithoutStrandingTheRun() {
        UUID runId = UUID.randomUUID();
        when(repository.findRun(user, runId)).thenReturn(Optional.of(run(runId, user, "QUEUED", List.of("Backend Engineer"))));
        when(repository.cancelRequested(user, runId)).thenReturn(false);
        when(search.search(user, "Backend Engineer jobs", 6)).thenReturn(new WebSearchResult("q",
                List.of(new WebSearchResult.ResultItem("Senior Backend Engineer - Acme", JOB_URL, "北京", 0.9)), 1, "1s"));
        when(fetcher.fetch(JOB_URL)).thenReturn(new SafePageFetcher.Page(JOB_URL, 200, PAGE,
                SafePageFetcher.STATUS_OPEN, null));
        when(repository.findPosting("lever:" + LEVER_ID)).thenReturn(Optional.empty());
        when(repository.insertPosting(any())).thenThrow(new IllegalStateException("value too long"));

        service.perform(user, runId);

        verify(repository).finish(eq(user), eq(runId), eq("PARTIAL"), eq(1), eq(1), eq(0), eq(0), eq(1), eq(1),
                contains("CANDIDATE_FAILED"));
    }

    @Test void aProgressWriteFailureFinalizesTheRunBeforeTheJobFails() {
        UUID runId = UUID.randomUUID();
        when(repository.findRun(user, runId)).thenReturn(Optional.of(run(runId, user, "QUEUED", List.of("Backend Engineer"))));
        when(repository.cancelRequested(user, runId)).thenReturn(false);
        when(search.search(user, "Backend Engineer jobs", 6)).thenReturn(new WebSearchResult("q", List.of(), 0, "1s"));
        doThrow(new IllegalStateException("database unavailable")).when(repository).saveProgress(any(), any(), anyInt(),
                anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());

        assertThatThrownBy(() -> service.perform(user, runId)).isInstanceOf(IllegalStateException.class);

        verify(repository).finish(eq(user), eq(runId), eq("FAILED"), eq(1), eq(0), eq(0), eq(0), eq(0), eq(1),
                contains("RUN_FAILED"));
    }

    @Test void oneFailedQueryKeepsTheOtherResultsAsPartialSuccess() {
        UUID runId = UUID.randomUUID();
        when(repository.findRun(user, runId)).thenReturn(Optional.of(run(runId, user, "QUEUED",
                List.of("Backend Engineer", "Data Analyst"))));
        when(repository.cancelRequested(user, runId)).thenReturn(false);
        when(search.search(user, "Backend Engineer jobs", 6)).thenThrow(new IllegalStateException("Tavily 请求过于频繁"));
        when(search.search(user, "Data Analyst jobs", 6)).thenReturn(new WebSearchResult("q", List.of(), 0, "1s"));

        service.perform(user, runId);

        verify(repository).finish(eq(user), eq(runId), eq("PARTIAL"), eq(2), eq(0), eq(0), eq(0), eq(0), eq(1),
                contains("SEARCH_FAILED"));
    }

    @Test void allQueriesFailingMarksTheRunFailed() {
        UUID runId = UUID.randomUUID();
        var failed = run(runId, user, "QUEUED", List.of("Backend Engineer"));
        when(repository.findRun(user, runId)).thenReturn(Optional.of(failed));
        when(repository.cancelRequested(user, runId)).thenReturn(false);
        when(search.search(any(), anyString(), anyInt())).thenThrow(new IllegalStateException("no key"));
        service.perform(user, runId);
        verify(repository).finish(eq(user), eq(runId), eq("FAILED"), eq(1), eq(0), eq(0), eq(0), eq(0), eq(1), anyString());
    }

    @Test void retryCreatesANewRunWithTheSameCriteriaInsteadOfReusingTheSavedResults() {
        UUID runId = UUID.randomUUID();
        var previous = run(runId, user, "FAILED", List.of("Backend Engineer"));
        var newRunLookups = new java.util.HashMap<UUID, Integer>();
        when(repository.findRun(eq(user), any(UUID.class))).thenAnswer(call -> {
            UUID id = call.getArgument(1);
            if (id.equals(runId)) return Optional.of(previous);
            int lookup = newRunLookups.merge(id, 1, Integer::sum);
            return lookup == 1 ? Optional.empty() : Optional.of(run(id, user, "QUEUED", List.of("Backend Engineer")));
        });
        when(repository.findActiveRun(user)).thenReturn(Optional.empty());

        var retried = service.retry(user, runId);

        assertThat(retried.status()).isEqualTo("QUEUED");
        verify(repository).insertRun(any(UUID.class), eq(user), eq(List.of("Backend Engineer")), eq(List.of()), eq(List.of()));
    }

    @Test void cancelStopsBeforeSearchingAndLeavesAlreadySavedResultsAlone() {
        UUID runId = UUID.randomUUID();
        when(repository.findRun(user, runId)).thenReturn(Optional.of(run(runId, user, "RUNNING", List.of("Backend Engineer"))));
        when(repository.cancelRequested(user, runId)).thenReturn(true);

        service.perform(user, runId);

        verify(search, never()).search(any(), anyString(), anyInt());
        verify(repository).finish(eq(user), eq(runId), eq("CANCELED"), eq(0), eq(0), eq(0), eq(0), eq(0), eq(0), anyString());
    }

    @Test void onlyAnExplicitAppliedConfirmationUpdatesTheResumeCounterOnce() {
        UUID postingId = UUID.randomUUID();
        when(repository.findView(user, postingId)).thenReturn(Optional.of(view(postingId, null)));
        when(repository.markApplied(user, postingId)).thenReturn(true, false);

        assertThatThrownBy(() -> service.markApplied(user, postingId, false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(rhythm, never()).incrementToday(any(), any());

        service.markApplied(user, postingId, true);
        verify(rhythm).incrementToday(user, ApplicationMetric.RESUME);

        service.markApplied(user, postingId, true);
        verify(rhythm, times(1)).incrementToday(user, ApplicationMetric.RESUME);
    }

    @Test void foreignPostingActionsAreNotFoundAndListsAreOwnerScoped() {
        when(repository.updateState(user, UUID.fromString("00000000-0000-0000-0000-000000000001"), "SAVED"))
                .thenReturn(false);
        assertThatThrownBy(() -> service.updateState(user, UUID.fromString("00000000-0000-0000-0000-000000000001"), "SAVED"))
                .isInstanceOf(ResourceNotFoundException.class);

        when(repository.listPostings(user, "NEW", "后端", 20, 0)).thenReturn(List.of());
        assertThat(service.list(user, "NEW", "后端", 20, 0)).isEmpty();
        verify(repository).listPostings(user, "NEW", "后端", 20, 0);
    }
}
