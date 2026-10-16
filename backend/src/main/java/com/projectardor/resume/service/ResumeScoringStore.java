package com.projectardor.resume.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.resume.domain.ResumeAnalysis;
import com.projectardor.resume.repository.ResumeAnalysisRepository;

/** Only the short publication phase is transactional; never holds locks during an LLM call. */
@Service
public class ResumeScoringStore {
    private final JdbcTemplate jdbc;
    private final ResumeAnalysisRepository analyses;

    public ResumeScoringStore(JdbcTemplate jdbc, ResumeAnalysisRepository analyses) {
        this.jdbc = jdbc;
        this.analyses = analyses;
    }

    @Transactional
    public ResumeAnalysis publish(UUID userId, UUID resumeId, String key, Map<String, Object> candidate, String model) {
        if (jdbc.queryForList("SELECT id FROM resumes WHERE id=? AND user_id=? FOR KEY SHARE", UUID.class, resumeId, userId).isEmpty()) {
            throw new ResourceNotFoundException("简历不存在");
        }
        jdbc.execute(connection -> {
            var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))");
            statement.setString(1, "resume-score:" + userId + ":" + key);
            return statement;
        }, (org.springframework.jdbc.core.PreparedStatementCallback<Void>) statement -> { statement.execute(); return null; });
        var existing = analyses.findByResumeIdAndUserIdAndEvaluationKey(resumeId, userId, key);
        if (existing.isPresent()) return existing.get();
        var winner = analyses.findFirstByUserIdAndEvaluationKeyOrderByCreatedAtAsc(userId, key);
        return analyses.saveAndFlush(ResumeAnalysis.scored(userId, resumeId,
                winner.map(ResumeAnalysis::getAnalysis).orElse(candidate),
                winner.map(ResumeAnalysis::getModelName).orElse(model), key));
    }
}
