package com.projectardor.learning.practice.mockinterview.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * V97 is additive: the classic rows must survive it unchanged, and every new table must bind its
 * rows to one owner. Only V1, V27 and V97 run here; the full chain is covered by the migrate job.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AdaptiveInterviewSchemaPostgresTests {
    private JdbcTemplate root, jdbc;
    private String schema;
    private UUID owner, stranger, sessionId;

    @BeforeEach
    void prepare() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:5432/ardor");
        root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        schema = "ardor_adaptive_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&ApplicationName=" + schema, "ardor", "acceptance-only");
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__initialize_core_schema.sql"),
                new ClassPathResource("db/migration/V27__add_interview_question_leetcode_slug.sql")).execute(source);
        owner = UUID.randomUUID();
        stranger = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        for (UUID user : List.of(owner, stranger)) {
            jdbc.update("INSERT INTO users(id, email, password_hash) VALUES (?, ?, 'not-a-real-hash')",
                    user, user + "@ardor.invalid");
        }
    }

    @AfterEach
    void cleanup() {
        if (root != null && schema != null) root.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private void applyV97() {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V97__add_adaptive_interview.sql")).execute(jdbc.getDataSource());
    }

    private void insertLegacySession() {
        jdbc.update("INSERT INTO interview_sessions(id, user_id, target_role, status) VALUES (?, ?, 'Backend', 'IN_PROGRESS')",
                sessionId, owner);
    }

    private void insertAdaptiveSession() {
        jdbc.update("""
                INSERT INTO interview_sessions(id, user_id, target_role, status, mode, seniority, duration_minutes, deep_dive)
                VALUES (?, ?, 'Backend', 'IN_PROGRESS', 'ADAPTIVE', 'SENIOR', 30, TRUE)
                """, sessionId, owner);
    }

    private UUID insertQuestion(UUID user, int sequence, String competency, int difficulty, UUID parent) {
        return insertQuestion(user, sessionId, sequence, competency, difficulty, parent);
    }

    private UUID insertQuestion(UUID user, UUID session, int sequence, String competency, int difficulty, UUID parent) {
        UUID questionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_questions(id, user_id, interview_session_id, sequence_number, question_text,
                    question_type, evaluation_criteria, competency, knowledge_point, difficulty, rubric, origin, parent_question_id)
                VALUES (?, ?, ?, ?, 'Why G1?', 'TECHNICAL', '[]'::jsonb, ?, 'GC', ?, '{"mustHavePoints":["Region"]}'::jsonb, 'PLAN', ?)
                """, questionId, user, session, sequence, competency, difficulty, parent);
        return questionId;
    }

    /** A session owned by the other user, so a cross-owner reference fails on the FK under test, not the session FK. */
    private UUID strangerSessionWithQuestion() {
        UUID foreignSession = UUID.randomUUID();
        jdbc.update("INSERT INTO interview_sessions(id, user_id, target_role, status) VALUES (?, ?, 'Backend', 'IN_PROGRESS')",
                foreignSession, stranger);
        return insertQuestion(stranger, foreignSession, 1, null, 3, null);
    }

    private UUID insertAnswer(UUID questionId) {
        UUID answerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_answers(id, user_id, interview_session_id, interview_question_id, answer_text)
                VALUES (?, ?, ?, ?, 'Because regions bound the pause.')
                """, answerId, owner, sessionId, questionId);
        return answerId;
    }

    @Test void existingSessionsBecomeClassicWithoutLosingTheirState() {
        insertLegacySession();
        applyV97();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM interview_sessions WHERE id = ?", sessionId);
        assertThat(row.get("mode")).isEqualTo("CLASSIC");
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("target_role")).isEqualTo("Backend");
        assertThat(row.get("deep_dive")).isEqualTo(false);
        assertThat(row.get("duration_minutes")).isNull();
        assertThat(row.get("job_description")).isNull();
    }

    @Test void classicQuestionsKeepNullAdaptiveColumnsAndCannotBeGivenADifficulty() {
        insertLegacySession();
        applyV97();
        UUID questionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_questions(id, user_id, interview_session_id, sequence_number, question_text, evaluation_criteria)
                VALUES (?, ?, ?, 1, 'Classic question', '["Correctness"]'::jsonb)
                """, questionId, owner, sessionId);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM interview_questions WHERE id = ?", questionId);
        assertThat(row.get("difficulty")).isNull();
        assertThat(row.get("rubric")).isNull();
        assertThat(row.get("competency")).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE interview_questions SET difficulty = 6 WHERE id = ?", questionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void aTurnIsScoredOnceAndCannotBeAttachedToAnotherOwnersQuestion() {
        applyV97();
        insertAdaptiveSession();
        UUID questionId = insertQuestion(owner, 1, "JVM", 3, null);
        UUID answerId = insertAnswer(questionId);
        UUID evaluationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_turn_evaluations(id, user_id, interview_session_id, interview_question_id,
                    interview_answer_id, answer_kind, score, confidence, evaluation, prompt_version)
                VALUES (?, ?, ?, ?, ?, 'ANSWER', 3, 0.80, '{"coveredPoints":["Region"]}'::jsonb, 'adaptive-eval-v1')
                """, evaluationId, owner, sessionId, questionId, answerId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO interview_turn_evaluations(id, user_id, interview_session_id, interview_question_id,
                    interview_answer_id, answer_kind, score, confidence, prompt_version)
                VALUES (?, ?, ?, ?, ?, 'ANSWER', 2, 0.50, 'adaptive-eval-v1')
                """, UUID.randomUUID(), owner, sessionId, questionId, answerId))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID foreignQuestion = strangerSessionWithQuestion();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO interview_turn_evaluations(id, user_id, interview_session_id, interview_question_id,
                    interview_answer_id, answer_kind, score, confidence, prompt_version)
                VALUES (?, ?, ?, ?, ?, 'ANSWER', 2, 0.50, 'adaptive-eval-v1')
                """, UUID.randomUUID(), owner, sessionId, foreignQuestion, answerId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void aClarificationTurnCannotCarryAScoreAndAScoredTurnMustCarryOne() {
        applyV97();
        insertAdaptiveSession();
        UUID questionId = insertQuestion(owner, 1, "JVM", 3, null);
        UUID answerId = insertAnswer(questionId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO interview_turn_evaluations(id, user_id, interview_session_id, interview_question_id,
                    interview_answer_id, answer_kind, score, confidence, prompt_version)
                VALUES (?, ?, ?, ?, ?, 'CLARIFICATION', 2, 0.50, 'adaptive-eval-v1')
                """, UUID.randomUUID(), owner, sessionId, questionId, answerId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO interview_turn_evaluations(id, user_id, interview_session_id, interview_question_id,
                    interview_answer_id, answer_kind, prompt_version)
                VALUES (?, ?, ?, ?, ?, 'ANSWER', 'adaptive-eval-v1')
                """, UUID.randomUUID(), owner, sessionId, questionId, answerId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                INSERT INTO interview_turn_evaluations(id, user_id, interview_session_id, interview_question_id,
                    interview_answer_id, answer_kind, prompt_version)
                VALUES (?, ?, ?, ?, ?, 'CLARIFICATION', 'adaptive-eval-v1')
                """, UUID.randomUUID(), owner, sessionId, questionId, answerId);
        assertThat(jdbc.queryForObject("SELECT score FROM interview_turn_evaluations WHERE interview_question_id = ?",
                Integer.class, questionId)).isNull();
    }

    @Test void theRubricScaleAndConfidenceRangeAreEnforcedByTheDatabase() {
        applyV97();
        insertAdaptiveSession();
        UUID questionId = insertQuestion(owner, 1, "JVM", 3, null);
        UUID answerId = insertAnswer(questionId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO interview_turn_evaluations(id, user_id, interview_session_id, interview_question_id,
                    interview_answer_id, answer_kind, score, confidence, prompt_version)
                VALUES (?, ?, ?, ?, ?, 'ANSWER', 5, 0.50, 'adaptive-eval-v1')
                """, UUID.randomUUID(), owner, sessionId, questionId, answerId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO interview_turn_evaluations(id, user_id, interview_session_id, interview_question_id,
                    interview_answer_id, answer_kind, score, confidence, prompt_version)
                VALUES (?, ?, ?, ?, ?, 'ANSWER', 3, 1.50, 'adaptive-eval-v1')
                """, UUID.randomUUID(), owner, sessionId, questionId, answerId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void aCompetencyIsAssessedOncePerSessionAndStartsUnverified() {
        applyV97();
        insertAdaptiveSession();
        UUID assessmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_competency_assessments(id, user_id, interview_session_id, competency, jd_priority)
                VALUES (?, ?, ?, 'JVM', 5)
                """, assessmentId, owner, sessionId);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM interview_competency_assessments WHERE id = ?", assessmentId);
        assertThat(row.get("state")).isEqualTo("UNVERIFIED");
        assertThat(row.get("estimated_level")).isNull();
        assertThat(row.get("evidence_count")).isEqualTo(0);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO interview_competency_assessments(id, user_id, interview_session_id, competency)
                VALUES (?, ?, ?, 'JVM')
                """, UUID.randomUUID(), owner, sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE interview_competency_assessments SET state = 'CANNOT_DO' WHERE id = ?
                """, assessmentId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void policyDecisionsAreAppendOnlyAndNumberedOncePerSession() {
        applyV97();
        insertAdaptiveSession();
        jdbc.update("""
                INSERT INTO interview_policy_decisions(id, user_id, interview_session_id, sequence_number, action, reason, decision)
                VALUES (?, ?, ?, 1, 'OPEN', '开场', '{"competency":"JVM"}'::jsonb)
                """, UUID.randomUUID(), owner, sessionId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO interview_policy_decisions(id, user_id, interview_session_id, sequence_number, action, reason, decision)
                VALUES (?, ?, ?, 1, 'RAISE', '重复序号', '{}'::jsonb)
                """, UUID.randomUUID(), owner, sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO interview_policy_decisions(id, user_id, interview_session_id, sequence_number, action, reason, decision)
                VALUES (?, ?, ?, 2, 'ESCALATE', '未知动作', '{}'::jsonb)
                """, UUID.randomUUID(), owner, sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void aFollowUpCannotPointAtAnotherUsersQuestion() {
        applyV97();
        insertAdaptiveSession();
        UUID parent = insertQuestion(owner, 1, "JVM", 3, null);
        insertQuestion(owner, 2, "JVM", 4, parent);
        UUID foreignParent = strangerSessionWithQuestion();
        assertThatThrownBy(() -> insertQuestion(owner, 3, "JVM", 4, foreignParent))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void deletingASessionTakesItsPlanTurnsDecisionsAndAssessmentsWithIt() {
        applyV97();
        insertAdaptiveSession();
        UUID questionId = insertQuestion(owner, 1, "JVM", 3, null);
        UUID answerId = insertAnswer(questionId);
        jdbc.update("INSERT INTO interview_plans(id, user_id, interview_session_id, plan, prompt_version) VALUES (?, ?, ?, '{}'::jsonb, 'v1')",
                UUID.randomUUID(), owner, sessionId);
        jdbc.update("""
                INSERT INTO interview_turn_evaluations(id, user_id, interview_session_id, interview_question_id,
                    interview_answer_id, answer_kind, score, confidence, prompt_version)
                VALUES (?, ?, ?, ?, ?, 'ANSWER', 3, 0.80, 'v1')
                """, UUID.randomUUID(), owner, sessionId, questionId, answerId);
        jdbc.update("""
                INSERT INTO interview_policy_decisions(id, user_id, interview_session_id, sequence_number, action, reason, decision)
                VALUES (?, ?, ?, 1, 'OPEN', '开场', '{}'::jsonb)
                """, UUID.randomUUID(), owner, sessionId);
        jdbc.update("""
                INSERT INTO interview_competency_assessments(id, user_id, interview_session_id, competency)
                VALUES (?, ?, ?, 'JVM')
                """, UUID.randomUUID(), owner, sessionId);

        jdbc.update("DELETE FROM interview_sessions WHERE id = ?", sessionId);

        for (String table : List.of("interview_plans", "interview_questions", "interview_answers",
                "interview_turn_evaluations", "interview_policy_decisions", "interview_competency_assessments")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE interview_session_id = ?",
                    Integer.class, sessionId)).as(table).isZero();
        }
    }
}