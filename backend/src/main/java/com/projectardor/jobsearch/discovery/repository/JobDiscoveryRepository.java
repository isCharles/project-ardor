package com.projectardor.jobsearch.discovery.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence for the discovery catalog, each user's inbox state and the run
 * record. All owner-facing reads and writes include user_id; posting rows carry
 * only public job facts, never a user's saved/ignored choice.
 */
@Repository
public class JobDiscoveryRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JobDiscoveryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Run(UUID id, UUID userId, UUID jobId, String status,
            List<String> roles, List<String> locations, List<String> keywords, List<String> queries,
            int queriesDone, int candidateCount, int newCount, int updatedCount, int skippedCount,
            int errorCount, String errors, boolean cancelRequested,
            Instant createdAt, Instant startedAt, Instant finishedAt) {}

    public record Posting(UUID id, String canonicalKey, String company, String title, String location,
            String applyUrl, String source, String salaryText, String experienceText, String deadlineText,
            String linkStatus, Instant firstSeenAt, Instant lastVerifiedAt) {}

    public record Discovery(UUID id, UUID postingId, String state, String matchReason, String matchEvidence,
            String discoveryQuery, Instant discoveredAt, Instant lastSeenAt, Instant appliedAt) {}

    public record Source(String source, String url, Instant discoveredAt) {}

    public record PostingView(Posting posting, Discovery discovery, List<Source> sources) {}

    public record Preferences(List<String> roles, List<String> locations, List<String> keywords) {}

    public record Summary(int inbox, int saved, int ignored, UUID activeRunId) {}

    public Optional<Run> findRun(UUID userId, UUID runId) {
        return jdbc.query("SELECT * FROM job_discovery_runs WHERE id = ? AND user_id = ?", this::mapRun, runId, userId)
                .stream().findFirst();
    }

    public Optional<Run> findActiveRun(UUID userId) {
        return jdbc.query("""
                SELECT * FROM job_discovery_runs WHERE user_id = ? AND status IN ('QUEUED','RUNNING')
                ORDER BY created_at DESC LIMIT 1
                """, this::mapRun, userId).stream().findFirst();
    }

    public void insertRun(UUID id, UUID userId, List<String> roles, List<String> locations, List<String> keywords) {
        jdbc.update("""
                INSERT INTO job_discovery_runs(id, user_id, job_id, roles, locations, keywords)
                VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))
                """, id, userId, id, json(roles), json(locations), json(keywords));
    }

    public void markRunning(UUID userId, UUID runId, List<String> queries) {
        jdbc.update("""
                UPDATE job_discovery_runs SET status = 'RUNNING', queries = CAST(? AS jsonb),
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status IN ('QUEUED','RUNNING')
                """, json(queries), runId, userId);
    }

    public void saveProgress(UUID userId, UUID runId, int queriesDone, int candidateCount, int newCount,
            int updatedCount, int skippedCount, int errorCount, String errorsJson) {
        jdbc.update("""
                UPDATE job_discovery_runs SET queries_done = ?, candidate_count = ?, new_count = ?,
                    updated_count = ?, skipped_count = ?, error_count = ?, errors = CAST(? AS jsonb),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING'
                """, queriesDone, candidateCount, newCount, updatedCount, skippedCount, errorCount,
                errorsJson, runId, userId);
    }

    public void finish(UUID userId, UUID runId, String status, int queriesDone, int candidateCount,
            int newCount, int updatedCount, int skippedCount, int errorCount, String errorsJson) {
        jdbc.update("""
                UPDATE job_discovery_runs SET status = ?, queries_done = ?, candidate_count = ?,
                    new_count = ?, updated_count = ?, skipped_count = ?, error_count = ?, errors = CAST(? AS jsonb),
                    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status IN ('RUNNING','QUEUED')
                """, status, queriesDone, candidateCount, newCount, updatedCount, skippedCount, errorCount,
                errorsJson, runId, userId);
    }

    public boolean requestCancel(UUID userId, UUID runId) {
        return jdbc.update("""
                UPDATE job_discovery_runs SET cancel_requested = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status IN ('QUEUED','RUNNING')
                """, runId, userId) == 1;
    }

    public void cancelQueued(UUID userId, UUID runId) {
        jdbc.update("""
                UPDATE job_discovery_runs SET status = 'CANCELED', finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'QUEUED'
                """, runId, userId);
    }

    public void resetForRetry(UUID userId, UUID runId) {
        jdbc.update("""
                UPDATE job_discovery_runs SET status = 'QUEUED', queries = '[]'::jsonb, queries_done = 0,
                    candidate_count = 0, new_count = 0, updated_count = 0, skipped_count = 0,
                    error_count = 0, errors = '[]'::jsonb, cancel_requested = FALSE,
                    started_at = NULL, finished_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status IN ('FAILED','PARTIAL')
                """, runId, userId);
    }

    public boolean cancelRequested(UUID userId, UUID runId) {
        Boolean value = jdbc.queryForObject(
                "SELECT cancel_requested FROM job_discovery_runs WHERE id = ? AND user_id = ?",
                Boolean.class, runId, userId);
        return Boolean.TRUE.equals(value);
    }

    public Optional<Posting> findPosting(String canonicalKey) {
        return jdbc.query("SELECT * FROM job_postings WHERE canonical_key = ?", this::mapPosting, canonicalKey)
                .stream().findFirst();
    }

    public Posting insertPosting(Posting posting) {
        int inserted = jdbc.update("""
                INSERT INTO job_postings(id, canonical_key, company, title, location, apply_url, source,
                    salary_text, experience_text, deadline_text, link_status, first_seen_at, last_verified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (canonical_key) DO NOTHING
                """, posting.id(), posting.canonicalKey(), posting.company(), posting.title(), posting.location(),
                posting.applyUrl(), posting.source(), posting.salaryText(), posting.experienceText(),
                posting.deadlineText(), posting.linkStatus(), timestamp(posting.firstSeenAt()),
                timestamp(posting.lastVerifiedAt()));
        if (inserted == 1) return posting;
        return findPosting(posting.canonicalKey()).orElseThrow();
    }

    public void refreshPosting(Posting posting) {
        jdbc.update("""
                UPDATE job_postings SET company = ?, title = ?, location = COALESCE(?, location),
                    apply_url = ?, source = ?, salary_text = COALESCE(?, salary_text),
                    experience_text = COALESCE(?, experience_text), deadline_text = COALESCE(?, deadline_text),
                    link_status = ?, last_verified_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, posting.company(), posting.title(), posting.location(), posting.applyUrl(), posting.source(),
                posting.salaryText(), posting.experienceText(), posting.deadlineText(), posting.linkStatus(),
                timestamp(posting.lastVerifiedAt()), posting.id());
    }

    public void addSource(UUID postingId, String source, String url) {
        jdbc.update("""
                INSERT INTO job_posting_sources(posting_id, source, url) VALUES (?, ?, ?)
                ON CONFLICT (posting_id, source, url) DO NOTHING
                """, postingId, source, url);
    }

    /** Returns true when this search created the user's row rather than refreshing one. */
    public boolean insertDiscovery(UUID id, UUID userId, UUID postingId, String matchReason,
            String matchEvidence, String discoveryQuery) {
        return jdbc.update("""
                INSERT INTO job_posting_discoveries(id, user_id, posting_id, state, match_reason, match_evidence, discovery_query)
                VALUES (?, ?, ?, 'NEW', ?, ?, ?)
                ON CONFLICT (user_id, posting_id) DO NOTHING
                """, id, userId, postingId, matchReason, matchEvidence, discoveryQuery) == 1;
    }

    public void touchDiscovery(UUID userId, UUID postingId, String matchReason, String matchEvidence,
            String discoveryQuery) {
        jdbc.update("""
                UPDATE job_posting_discoveries SET last_seen_at = CURRENT_TIMESTAMP,
                    match_reason = COALESCE(?, match_reason), match_evidence = COALESCE(?, match_evidence),
                    discovery_query = COALESCE(?, discovery_query), updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND posting_id = ?
                """, matchReason, matchEvidence, discoveryQuery, userId, postingId);
    }

    public List<PostingView> listPostings(UUID userId, String state, String query, int limit, int offset) {
        String filter = query == null || query.isBlank() ? null : "%" + query.strip() + "%";
        List<Posting> postings = jdbc.query("""
                SELECT p.*
                FROM job_posting_discoveries d JOIN job_postings p ON p.id = d.posting_id
                WHERE d.user_id = ?
                  AND (CAST(? AS varchar) IS NULL OR d.state = ?)
                  AND (CAST(? AS varchar) IS NULL OR p.company ILIKE ? OR p.title ILIKE ?
                       OR COALESCE(p.location,'') ILIKE ? OR COALESCE(d.match_reason,'') ILIKE ?)
                ORDER BY d.discovered_at DESC, d.id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> mapPosting(rs, 0), userId, state, state, filter, filter, filter, filter, filter,
                limit, offset).stream()
                .map(posting -> new Posting(posting.id(), posting.canonicalKey(), posting.company(), posting.title(),
                        posting.location(), posting.applyUrl(), posting.source(), posting.salaryText(),
                        posting.experienceText(), posting.deadlineText(), posting.linkStatus(),
                        posting.firstSeenAt(), posting.lastVerifiedAt()))
                .toList();
        return views(userId, postings);
    }

    public Optional<PostingView> findView(UUID userId, UUID postingId) {
        List<PostingView> views = jdbc.query("""
                SELECT p.*, d.id AS d_id, d.state AS d_state, d.match_reason, d.match_evidence,
                       d.discovery_query, d.discovered_at, d.last_seen_at, d.applied_at
                FROM job_posting_discoveries d JOIN job_postings p ON p.id = d.posting_id
                WHERE d.user_id = ? AND d.posting_id = ?
                """, (rs, row) -> new PostingView(mapPosting(rs, row),
                        mapDiscovery(rs, row), List.of()), userId, postingId);
        return views.isEmpty() ? Optional.empty() : Optional.of(withSources(views.getFirst()));
    }

    public boolean updateState(UUID userId, UUID postingId, String state) {
        return jdbc.update("""
                UPDATE job_posting_discoveries SET state = ?, updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND posting_id = ?
                """, state, userId, postingId) == 1;
    }

    /** Only the first explicit confirmation records a send; returns false when already applied. */
    public boolean markApplied(UUID userId, UUID postingId) {
        return jdbc.update("""
                UPDATE job_posting_discoveries SET applied_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND posting_id = ? AND applied_at IS NULL
                """, userId, postingId) == 1;
    }

    public Optional<Preferences> preferences(UUID userId) {
        return jdbc.query("""
                SELECT roles, locations, keywords FROM job_discovery_preferences WHERE user_id = ?
                """, (rs, row) -> new Preferences(strings(rs.getString("roles")), strings(rs.getString("locations")),
                strings(rs.getString("keywords"))), userId).stream().findFirst();
    }

    public void savePreferences(UUID userId, List<String> roles, List<String> locations, List<String> keywords) {
        jdbc.update("""
                INSERT INTO job_discovery_preferences(user_id, roles, locations, keywords)
                VALUES (?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))
                ON CONFLICT (user_id) DO UPDATE SET roles = EXCLUDED.roles, locations = EXCLUDED.locations,
                    keywords = EXCLUDED.keywords, updated_at = CURRENT_TIMESTAMP
                """, userId, json(roles), json(locations), json(keywords));
    }

    public Summary summary(UUID userId) {
        int inbox = 0, saved = 0, ignored = 0;
        for (var row : jdbc.queryForList("""
                SELECT state, COUNT(*) AS total FROM job_posting_discoveries
                WHERE user_id = ? GROUP BY state
                """, userId)) {
            int total = ((Number) row.get("total")).intValue();
            switch (String.valueOf(row.get("state"))) {
                case "NEW" -> inbox = total;
                case "SAVED" -> saved = total;
                case "IGNORED" -> ignored = total;
                default -> { }
            }
        }
        UUID active = findActiveRun(userId).map(Run::id).orElse(null);
        return new Summary(inbox, saved, ignored, active);
    }

    private List<PostingView> views(UUID userId, List<Posting> postings) {
        List<PostingView> views = new ArrayList<>();
        for (Posting posting : postings) {
            findView(userId, posting.id()).ifPresent(views::add);
        }
        return views;
    }

    private PostingView withSources(PostingView view) {
        List<Source> sources = jdbc.query("""
                SELECT source, url, discovered_at FROM job_posting_sources WHERE posting_id = ?
                ORDER BY discovered_at ASC
                """, (rs, row) -> new Source(rs.getString(1), rs.getString(2), instant(rs, "discovered_at")),
                view.posting().id());
        return new PostingView(view.posting(), view.discovery(), sources);
    }

    private Run mapRun(ResultSet rs, int row) throws SQLException {
        return new Run(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getString("status"),
                strings(rs.getString("roles")), strings(rs.getString("locations")),
                strings(rs.getString("keywords")), strings(rs.getString("queries")),
                rs.getInt("queries_done"), rs.getInt("candidate_count"), rs.getInt("new_count"),
                rs.getInt("updated_count"), rs.getInt("skipped_count"), rs.getInt("error_count"),
                rs.getString("errors"), rs.getBoolean("cancel_requested"),
                instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private Posting mapPosting(ResultSet rs, int row) throws SQLException {
        return new Posting(rs.getObject("id", UUID.class), rs.getString("canonical_key"),
                rs.getString("company"), rs.getString("title"), rs.getString("location"),
                rs.getString("apply_url"), rs.getString("source"), rs.getString("salary_text"),
                rs.getString("experience_text"), rs.getString("deadline_text"), rs.getString("link_status"),
                instant(rs, "first_seen_at"), instant(rs, "last_verified_at"));
    }

    private Discovery mapDiscovery(ResultSet rs, int row) throws SQLException {
        return new Discovery(rs.getObject("d_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getString("d_state"), rs.getString("match_reason"), rs.getString("match_evidence"),
                rs.getString("discovery_query"), instant(rs, "discovered_at"), instant(rs, "last_seen_at"),
                instant(rs, "applied_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private List<String> strings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            node.forEach(item -> { if (item.isTextual()) values.add(item.asText()); });
            return List.copyOf(values);
        } catch (RuntimeException malformed) {
            return List.of();
        }
    }
}
