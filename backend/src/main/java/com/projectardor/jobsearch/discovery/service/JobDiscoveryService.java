package com.projectardor.jobsearch.discovery.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

import tools.jackson.databind.ObjectMapper;

/**
 * Owns job discovery: saved criteria, a queued search run, deterministic dedupe
 * and each user's inbox state. Discovery never records an application; only the
 * explicit applied confirmation touches the existing resume counter.
 */
@Service
public class JobDiscoveryService {

    public static final String RESULT_PATH = "/app/job-search/discovery";
    private static final int MAX_ROLES = 5;
    private static final int MAX_LOCATIONS = 5;
    private static final int MAX_KEYWORDS = 8;
    private static final int MAX_QUERIES = 4;
    private static final int RESULTS_PER_QUERY = 6;
    private static final int MAX_CANDIDATES = 15;

    private final JobDiscoveryRepository repository;
    private final TavilySearchService search;
    private final SafePageFetcher fetcher;
    private final JobPageParser parser;
    private final ApplicationRhythmService rhythm;
    private final ProfileService profiles;
    private final AiJobStore jobs;
    private final MembershipService memberships;
    private final ObjectMapper mapper;

    public JobDiscoveryService(JobDiscoveryRepository repository, TavilySearchService search,
            SafePageFetcher fetcher, JobPageParser parser, ApplicationRhythmService rhythm,
            ProfileService profiles, AiJobStore jobs, MembershipService memberships, ObjectMapper mapper) {
        this.repository = repository;
        this.search = search;
        this.fetcher = fetcher;
        this.parser = parser;
        this.rhythm = rhythm;
        this.profiles = profiles;
        this.jobs = jobs;
        this.memberships = memberships;
        this.mapper = mapper;
    }

    public record Criteria(List<String> roles, List<String> locations, List<String> keywords) {}

    public record PreferencesView(List<String> roles, List<String> locations, List<String> keywords,
            List<String> suggestedRoles) {}

    public record ErrorItem(String query, String url, String code, String message) {}

    public record RunView(UUID id, UUID jobId, String status, List<String> roles, List<String> locations,
            List<String> keywords, List<String> queries, int queriesDone, int candidateCount, int newCount,
            int updatedCount, int skippedCount, int errorCount, List<ErrorItem> errors, boolean cancelRequested,
            Instant createdAt, Instant startedAt, Instant finishedAt, String resultPath) {}

    public record SourceView(String source, String url, Instant discoveredAt) {}

    public record PostingView(UUID id, String state, String company, String title, String location,
            String applyUrl, String source, String salaryText, String experienceText, String deadlineText,
            String linkStatus, Instant firstSeenAt, Instant lastVerifiedAt, Instant discoveredAt,
            Instant lastSeenAt, Instant appliedAt, String matchReason, String matchEvidence,
            String discoveryQuery, List<SourceView> sources) {}

    public record SummaryView(int inbox, int saved, int ignored, UUID activeRunId) {}

    @Transactional
    public RunView start(UUID userId, UUID requestId, Criteria criteria) {
        if (requestId == null) throw new IllegalArgumentException("请求 ID 不能为空");
        var existing = repository.findRun(userId, requestId);
        if (existing.isPresent()) return view(existing.get());
        Criteria normalized = normalize(criteria, true, userId);
        repository.savePreferences(userId, normalized.roles(), normalized.locations(), normalized.keywords());
        var active = repository.findActiveRun(userId);
        if (active.isPresent()) throw new IllegalStateException("已有进行中的岗位搜索，请等待完成或取消");
        repository.insertRun(requestId, userId, normalized.roles(), normalized.locations(), normalized.keywords());
        int priority = memberships.tier(userId).paid() ? 1 : 0;
        jobs.enqueue(userId, requestId, AiJobType.JOB_DISCOVERY, requestId, "{}", priority);
        return view(requireRun(userId, requestId));
    }

    @Transactional(readOnly = true)
    public RunView run(UUID userId, UUID runId) {
        return view(requireRun(userId, runId));
    }

    @Transactional(readOnly = true)
    public List<PostingView> list(UUID userId, String state, String query, int limit, int offset) {
        String normalizedState = normalizeState(state);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("每页数量必须在 1 到 50 之间");
        if (offset < 0) throw new IllegalArgumentException("分页偏移不能为负数");
        return repository.listPostings(userId, normalizedState, query, limit, offset).stream()
                .map(JobDiscoveryService::view).toList();
    }

    @Transactional
    public PostingView updateState(UUID userId, UUID postingId, String state) {
        String normalized = normalizeState(state);
        if (normalized == null) throw new IllegalArgumentException("岗位状态无效");
        if (!repository.updateState(userId, postingId, normalized)) throw notFound();
        return requireView(userId, postingId);
    }

    @Transactional
    public PostingView markApplied(UUID userId, UUID postingId, boolean confirmed) {
        if (!confirmed) throw new IllegalArgumentException("只有明确确认简历已发出后，才能标记为已投递");
        requireView(userId, postingId);
        if (repository.markApplied(userId, postingId)) {
            rhythm.incrementToday(userId, ApplicationMetric.RESUME);
        }
        return requireView(userId, postingId);
    }

    @Transactional
    public RunView cancel(UUID userId, UUID runId) {
        var run = requireRun(userId, runId);
        if (terminal(run.status())) return view(run);
        repository.requestCancel(userId, runId);
        if ("QUEUED".equals(run.status()) && run.jobId() != null) {
            try {
                jobs.cancel(userId, run.jobId());
                repository.cancelQueued(userId, runId);
            } catch (IllegalStateException alreadyStarted) {
                // The worker may claim it between the read and the cancel; its checkpoint will stop.
            }
        }
        return view(requireRun(userId, runId));
    }

    /** A retry is a new run with the same criteria; dedupe prevents duplicate inbox rows. */
    @Transactional
    public RunView retry(UUID userId, UUID runId) {
        var previous = requireRun(userId, runId);
        if (!List.of("FAILED", "PARTIAL", "CANCELED").contains(previous.status())) {
            throw new IllegalStateException("只有失败、部分成功或已取消的搜索可以重试");
        }
        return start(userId, UUID.randomUUID(), new Criteria(previous.roles(), previous.locations(), previous.keywords()));
    }

    @Transactional
    public PreferencesView preferences(UUID userId) {
        var saved = repository.preferences(userId).orElse(new JobDiscoveryRepository.Preferences(List.of(), List.of(), List.of()));
        return new PreferencesView(saved.roles(), saved.locations(), saved.keywords(), suggestedRoles(userId));
    }

    @Transactional
    public PreferencesView savePreferences(UUID userId, Criteria criteria) {
        Criteria normalized = normalize(criteria, false, userId);
        repository.savePreferences(userId, normalized.roles(), normalized.locations(), normalized.keywords());
        return new PreferencesView(normalized.roles(), normalized.locations(), normalized.keywords(), suggestedRoles(userId));
    }

    @Transactional(readOnly = true)
    public SummaryView summary(UUID userId) {
        var summary = repository.summary(userId);
        return new SummaryView(summary.inbox(), summary.saved(), summary.ignored(), summary.activeRunId());
    }

    /**
     * Runs inside the existing AI job worker. Deliberately not transactional: network
     * searches are slow and progress/cancel checkpoints must be visible to polling
     * readers while the run is still going.
     */
    public String perform(UUID userId, UUID runId) {
        var run = requireRun(userId, runId);
        if (terminal(run.status())) return resultPath(runId);
        if (repository.cancelRequested(userId, runId)) {
            repository.finish(userId, runId, "CANCELED", 0, 0, 0, 0, 0, 0, "[]");
            return resultPath(runId);
        }
        List<String> queries = queries(run.roles(), run.locations());
        repository.markRunning(userId, runId, queries);
        List<ErrorItem> errors = new ArrayList<>();
        int queriesDone = 0, candidates = 0, newCount = 0, updatedCount = 0, skipped = 0, searchFailures = 0;
        boolean canceled = false;
        outer:
        for (String query : queries) {
            if (repository.cancelRequested(userId, runId)) { canceled = true; break; }
            WebSearchResult result;
            try {
                result = search.search(userId, query, RESULTS_PER_QUERY);
            } catch (RuntimeException failure) {
                searchFailures++;
                addError(errors, query, null, "SEARCH_FAILED", safeMessage(failure));
                queriesDone++;
                saveProgress(userId, runId, queriesDone, candidates, newCount, updatedCount, skipped, errors);
                continue;
            }
            queriesDone++;
            for (WebSearchResult.ResultItem item : result.results()) {
                if (repository.cancelRequested(userId, runId)) { canceled = true; break outer; }
                if (candidates >= MAX_CANDIDATES) {
                    addError(errors, query, null, "CANDIDATE_LIMIT",
                            "本次已处理 " + MAX_CANDIDATES + " 个候选，剩余结果留到下次搜索");
                    break outer;
                }
                candidates++;
                Outcome outcome = processCandidate(userId, runId, item, run.roles(), run.locations(),
                        run.keywords(), query, errors);
                switch (outcome) {
                    case NEW -> newCount++;
                    case UPDATED -> updatedCount++;
                    case SKIPPED -> skipped++;
                }
            }
            saveProgress(userId, runId, queriesDone, candidates, newCount, updatedCount, skipped, errors);
        }
        if (repository.cancelRequested(userId, runId)) canceled = true;
        // Hitting the deliberate candidate cap leaves the rest for the next search;
        // it is a note, not a failed source.
        boolean sourceFailure = errors.stream().anyMatch(error -> !"CANDIDATE_LIMIT".equals(error.code()));
        String status = canceled ? "CANCELED"
                : searchFailures == queries.size() ? "FAILED"
                : searchFailures > 0 || sourceFailure ? "PARTIAL"
                : "COMPLETED";
        repository.finish(userId, runId, status, queriesDone, candidates, newCount, updatedCount,
                skipped, errors.size(), json(errors));
        return resultPath(runId);
    }

    private enum Outcome { NEW, UPDATED, SKIPPED }

    private Outcome processCandidate(UUID userId, UUID runId, WebSearchResult.ResultItem item,
            List<String> roles, List<String> locations, List<String> keywords, String query,
            List<ErrorItem> errors) {
        String key;
        try {
            key = JobPostingKeys.canonicalKey(item.url());
        } catch (IllegalArgumentException unsafe) {
            addError(errors, query, item.url(), "UNSAFE_URL", "链接不是可公开访问的 HTTP(S) 地址");
            return Outcome.SKIPPED;
        }
        SafePageFetcher.Page page;
        try {
            page = fetcher.fetch(item.url());
        } catch (IllegalArgumentException blocked) {
            addError(errors, query, item.url(), "BLOCKED_REDIRECT", safeMessage(blocked));
            return Outcome.SKIPPED;
        } catch (RuntimeException failure) {
            addError(errors, query, item.url(), "FETCH_FAILED", safeMessage(failure));
            return Outcome.SKIPPED;
        }
        JobPageParser.ParsedJob facts = parser.parse(page.body()).merge(fallbackFacts(item, page.finalUrl()));
        String title = firstNonBlank(facts.title(), cleanTitle(item.title()));
        String company = firstNonBlank(facts.company(), companyFromTitle(item.title()));
        if (title == null || company == null) {
            return Outcome.SKIPPED;
        }
        String location = facts.location();
        Match match = match(roles, locations, keywords, title, location, item.content());
        if (match == null) return Outcome.SKIPPED;
        Instant now = Instant.now();
        String source = JobPostingKeys.sourceHost(page.finalUrl());
        var existing = repository.findPosting(key);
        JobDiscoveryRepository.Posting planned = planPosting(existing, key, company, title, location,
                page.finalUrl(), source, facts, linkStatus(page), now);
        JobDiscoveryRepository.Posting stored = repository.insertPosting(planned);
        JobDiscoveryRepository.Posting posting = new JobDiscoveryRepository.Posting(stored.id(),
                planned.canonicalKey(), planned.company(), planned.title(), planned.location(),
                planned.applyUrl(), planned.source(), planned.salaryText(), planned.experienceText(),
                planned.deadlineText(), planned.linkStatus(), stored.firstSeenAt(), planned.lastVerifiedAt());
        repository.refreshPosting(posting);
        repository.addSource(posting.id(), source, item.url());
        boolean created = repository.insertDiscovery(UUID.randomUUID(), userId, posting.id(),
                match.reason(), match.evidence(), query);
        if (!created) repository.touchDiscovery(userId, posting.id(), match.reason(), match.evidence(), query);
        return created ? Outcome.NEW : Outcome.UPDATED;
    }

    private JobDiscoveryRepository.Posting planPosting(java.util.Optional<JobDiscoveryRepository.Posting> existing,
            String key, String company, String title, String location, String finalUrl, String source,
            JobPageParser.ParsedJob facts, String linkStatus, Instant now) {
        boolean verified = !SafePageFetcher.STATUS_UNKNOWN.equals(linkStatus);
        if (existing.isEmpty()) {
            return new JobDiscoveryRepository.Posting(UUID.randomUUID(), key, company, title, location, finalUrl,
                    source, facts.salaryText(), facts.experienceText(), facts.deadlineText(), linkStatus,
                    now, verified ? now : null);
        }
        var previous = existing.get();
        boolean preferNewUrl = JobPostingKeys.sourcePriority(finalUrl) <= JobPostingKeys.sourcePriority(previous.applyUrl());
        return new JobDiscoveryRepository.Posting(previous.id(), key,
                preferNewUrl ? company : previous.company(),
                preferNewUrl ? title : previous.title(),
                preferNewUrl ? location : previous.location(),
                preferNewUrl ? finalUrl : previous.applyUrl(), preferNewUrl ? source : previous.source(),
                facts.salaryText(), facts.experienceText(), facts.deadlineText(),
                verified ? linkStatus : previous.linkStatus(),
                previous.firstSeenAt(), verified ? now : previous.lastVerifiedAt());
    }

    private JobPageParser.ParsedJob fallbackFacts(WebSearchResult.ResultItem item, String finalUrl) {
        return new JobPageParser.ParsedJob(null, cleanTitle(item.title()), null, null, null, null);
    }

    private String linkStatus(SafePageFetcher.Page page) {
        return page.linkStatus();
    }

    private Match match(List<String> roles, List<String> locations, List<String> keywords,
            String title, String location, String snippet) {
        String titleLower = lower(title), locationLower = lower(location), snippetLower = lower(snippet);
        List<String> reasons = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (String role : roles) {
            String needle = lower(role);
            if (titleLower.contains(needle)) {
                reasons.add("标题包含目标岗位「" + role + "」");
                evidence.add("页面标题：" + title);
                break;
            }
            if (snippetLower.contains(needle)) {
                reasons.add("搜索摘要提及「" + role + "」");
                evidence.add("搜索摘要：" + excerpt(snippet));
                break;
            }
        }
        for (String keyword : keywords) {
            String needle = lower(keyword);
            if (titleLower.contains(needle) || snippetLower.contains(needle) || locationLower.contains(needle)) {
                reasons.add("包含关键词「" + keyword + "」");
            }
        }
        if (reasons.isEmpty()) return null;
        if (location != null && locations.stream().anyMatch(place ->
                locationLower.contains(lower(place)) || lower(place).contains(locationLower))) {
            reasons.add("地点匹配「" + location + "」");
            evidence.add("页面地点：" + location);
        }
        if (evidence.isEmpty()) evidence.add("搜索摘要：" + excerpt(snippet));
        return new Match(cap(String.join("；", reasons), 500), cap(String.join("；", evidence), 1000));
    }

    private record Match(String reason, String evidence) {}

    private Criteria normalize(Criteria criteria, boolean useProfileRoles, UUID userId) {
        List<String> roles = normalizeList(criteria == null ? null : criteria.roles(), MAX_ROLES, "目标岗位", 80);
        List<String> locations = normalizeList(criteria == null ? null : criteria.locations(), MAX_LOCATIONS, "地点", 80);
        List<String> keywords = normalizeList(criteria == null ? null : criteria.keywords(), MAX_KEYWORDS, "关键词", 60);
        if (roles.isEmpty() && useProfileRoles) {
            roles = normalizeList(profile(userId).targetRoles(), MAX_ROLES, "目标岗位", 80);
        }
        if (useProfileRoles && roles.isEmpty()) {
            throw new IllegalArgumentException("还没有可靠的目标岗位。请先补充目标岗位，或告诉我你想找的岗位方向");
        }
        return new Criteria(roles, locations, keywords);
    }

    private List<String> normalizeList(List<String> values, int max, String label, int maxLength) {
        if (values == null) return List.of();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String item = value.strip();
            if (item.length() > maxLength) throw new IllegalArgumentException(label + "单项不能超过 " + maxLength + " 个字符");
            seen.add(item);
        }
        if (seen.size() > max) throw new IllegalArgumentException(label + "最多 " + max + " 项");
        return List.copyOf(seen);
    }

    private List<String> suggestedRoles(UUID userId) {
        return profile(userId).targetRoles();
    }

    private ProfileView profile(UUID userId) {
        return profiles.view(userId);
    }

    private List<String> queries(List<String> roles, List<String> locations) {
        List<String> queries = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> places = locations.isEmpty() ? java.util.Collections.singletonList("") : locations;
        for (String role : roles) {
            for (String place : places) {
                String suffix = containsCjk(role) ? " 招聘" : " jobs";
                String query = role + (place.isBlank() ? "" : " " + place) + suffix;
                if (seen.add(query.toLowerCase(Locale.ROOT))) queries.add(query);
                if (queries.size() >= MAX_QUERIES) return queries;
            }
        }
        return queries;
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN);
    }

    private void saveProgress(UUID userId, UUID runId, int queriesDone, int candidates, int newCount,
            int updatedCount, int skipped, List<ErrorItem> errors) {
        repository.saveProgress(userId, runId, queriesDone, candidates, newCount, updatedCount, skipped,
                errors.size(), json(errors));
    }

    private void addError(List<ErrorItem> errors, String query, String url, String code, String message) {
        if (errors.size() >= 20) return;
        errors.add(new ErrorItem(query, url, code, cap(message, 240)));
    }

    private JobDiscoveryRepository.Run requireRun(UUID userId, UUID runId) {
        return repository.findRun(userId, runId).orElseThrow(() -> new ResourceNotFoundException("岗位搜索不存在"));
    }

    private PostingView requireView(UUID userId, UUID postingId) {
        return repository.findView(userId, postingId).map(JobDiscoveryService::view).orElseThrow(this::notFound);
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("岗位不存在或不属于当前用户");
    }

    private static boolean terminal(String status) {
        return List.of("COMPLETED", "PARTIAL", "FAILED", "CANCELED").contains(status);
    }

    private static String resultPath(UUID runId) {
        return RESULT_PATH + "?run=" + runId;
    }

    private RunView view(JobDiscoveryRepository.Run run) {
        List<ErrorItem> errors = new ArrayList<>();
        try {
            var node = mapper.readTree(run.errors() == null ? "[]" : run.errors());
            if (node.isArray()) {
                node.forEach(item -> errors.add(new ErrorItem(text(item, "query"), text(item, "url"),
                        text(item, "code"), text(item, "message"))));
            }
        } catch (RuntimeException malformed) {
            // A malformed error list must not hide the run itself.
        }
        return new RunView(run.id(), run.jobId(), run.status(), run.roles(), run.locations(), run.keywords(),
                run.queries(), run.queriesDone(), run.candidateCount(), run.newCount(), run.updatedCount(),
                run.skippedCount(), run.errorCount(), List.copyOf(errors), run.cancelRequested(),
                run.createdAt(), run.startedAt(), run.finishedAt(), resultPath(run.id()));
    }

    private static PostingView view(JobDiscoveryRepository.PostingView value) {
        var posting = value.posting();
        var discovery = value.discovery();
        return new PostingView(posting.id(), discovery.state(), posting.company(), posting.title(),
                posting.location(), posting.applyUrl(), posting.source(), posting.salaryText(),
                posting.experienceText(), posting.deadlineText(), posting.linkStatus(), posting.firstSeenAt(),
                posting.lastVerifiedAt(), discovery.discoveredAt(), discovery.lastSeenAt(), discovery.appliedAt(),
                discovery.matchReason(), discovery.matchEvidence(), discovery.discoveryQuery(),
                value.sources().stream().map(source -> new SourceView(source.source(), source.url(),
                        source.discoveredAt())).toList());
    }

    private static String text(tools.jackson.databind.JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private static String normalizeState(String state) {
        if (state == null || state.isBlank()) return null;
        String value = state.strip().toUpperCase(Locale.ROOT);
        return List.of("NEW", "SAVED", "IGNORED").contains(value) ? value : null;
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "请求失败" : message;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return null;
    }

    private static String cleanTitle(String value) {
        if (value == null) return null;
        String title = value.strip();
        for (String separator : List.of(" | ", " · ", " - ")) {
            int index = title.indexOf(separator);
            if (index > 0) title = title.substring(0, index);
        }
        int hiring = title.toLowerCase(Locale.ROOT).indexOf(" hiring ");
        if (hiring > 0) title = title.substring(hiring + 8);
        return title.isBlank() ? null : title;
    }

    private static String companyFromTitle(String value) {
        if (value == null) return null;
        String title = value.strip();
        int hiring = title.toLowerCase(Locale.ROOT).indexOf(" hiring ");
        if (hiring > 0) return title.substring(0, hiring).strip();
        int at = title.toLowerCase(Locale.ROOT).indexOf(" at ");
        if (at > 0) {
            String after = title.substring(at + 4).strip();
            int separator = after.indexOf(" | ");
            return (separator > 0 ? after.substring(0, separator) : after).strip();
        }
        int colon = title.indexOf(": ");
        if (colon > 0 && colon < 80) return title.substring(0, colon).strip();
        return null;
    }

    private static String excerpt(String value) {
        if (value == null) return "";
        String text = value.replaceAll("\\s+", " ").strip();
        return text.length() > 240 ? text.substring(0, 240) + "…" : text;
    }

    private static String cap(String value, int max) {
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }
}
