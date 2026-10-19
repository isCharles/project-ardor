package com.projectardor.recap.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.recap.domain.InterviewRecapJob;

/** Human confirmation is a persisted business boundary, not a model instruction. */
@Service
public class InterviewMinutesService {
    private final JdbcTemplate jdbc;
    private final LlmGateway llm;
    private final LlmJsonParser parser;
    private final TransactionTemplate tx;
    private final String skill;
    private final com.projectardor.usage.QuotaService quotas;

    public InterviewMinutesService(JdbcTemplate jdbc, LlmGateway llm, LlmJsonParser parser,
            org.springframework.transaction.PlatformTransactionManager manager,com.projectardor.usage.QuotaService quotas) throws java.io.IOException {
        this.jdbc = jdbc; this.llm = llm; this.parser = parser; this.tx = new TransactionTemplate(manager);
        this.quotas=quotas;
        this.skill = new ClassPathResource("skills/interview-minutes/SKILL.md").getContentAsString(StandardCharsets.UTF_8);
    }

    public void prepare(InterviewRecapJob job) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM interview_minutes WHERE id=? AND user_id=?)",
                Boolean.class, job.getId(), job.getUserId()))) return;
        List<String> parts = chunks(job.getRawContent(), 6000);
        StringBuilder draft = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            var result = llm.completeJson(job.getUserId(), skill,
                    "顺序片段 " + (i + 1) + "/" + parts.size() + "；原文字符位置 "
                    + parts.subList(0, i).stream().mapToInt(String::length).sum() + " 起。\n<transcript>\n" + parts.get(i) + "\n</transcript>");
            var root = parser.parseObject(result.content());
            String minutes = root.path("minutes").asText("").strip();
            if (minutes.isBlank() || minutes.length() > 20000) throw new IllegalStateException("初步纪要格式或长度无效，请重试或手动整理");
            draft.append("## 原文片段 ").append(i + 1).append("\n\n").append(minutes).append("\n\n");
        }
        if (draft.length() > 50000) throw new IllegalStateException("初步纪要过长，请手动整理，不会截断保存");
        jdbc.update("INSERT INTO interview_minutes(id,user_id,minutes) VALUES (?,?,?) ON CONFLICT(id) DO NOTHING",
                job.getId(), job.getUserId(), draft.toString());
    }

    public List<DraftSummary> list(UUID userId) {
        return jdbc.query("""
                SELECT j.id,j.status,j.created_at,m.revision,m.updated_at,
                    o.company FROM interview_recap_jobs j
                JOIN job_opportunities o ON o.id=j.job_opportunity_id AND o.user_id=j.user_id
                LEFT JOIN interview_minutes m ON m.id=j.id AND m.user_id=j.user_id
                WHERE j.user_id=? AND (m.id IS NOT NULL OR (j.recap_id IS NULL AND j.status<>'COMPLETED'))
                ORDER BY j.created_at DESC LIMIT 100
                """, (r,n) -> new DraftSummary(r.getObject("id",UUID.class),r.getString("company"),
                    r.getString("status"),r.getLong("revision")), userId);
    }

    public Draft get(UUID userId, UUID id) {
        var rows = jdbc.query("""
                SELECT j.raw_content,j.status,j.error_message,m.minutes,m.revision
                FROM interview_recap_jobs j LEFT JOIN interview_minutes m ON m.id=j.id AND m.user_id=j.user_id
                WHERE j.id=? AND j.user_id=?
                """, (r,n) -> new Draft(id,r.getString("raw_content"),r.getString("minutes"),r.getLong("revision"),
                    r.getString("status"),r.getString("error_message"),List.of()), id,userId);
        if (rows.isEmpty()) throw new ResourceNotFoundException("纪要不存在");
        Draft d = rows.getFirst();
        if (d.minutes()==null && "COMPLETED".equals(d.status())) throw new ResourceNotFoundException("旧任务没有可编辑纪要，请从已有报告重新整理");
        var reviews = jdbc.query("""
                SELECT id,revision,status,recap_id,error_message,minutes FROM interview_minutes_reviews
                WHERE draft_id=? AND user_id=? ORDER BY revision DESC
                """, (r,n) -> new Review(r.getObject("id",UUID.class),r.getLong("revision"),r.getString("status"),
                    r.getObject("recap_id",UUID.class),r.getString("error_message"),r.getString("minutes")), id,userId);
        return new Draft(id,d.rawContent(),d.minutes(),d.revision(),d.status(),d.errorMessage(),reviews);
    }

    public UUID sourceDraft(UUID userId, UUID recapId) {
        var ids=jdbc.query("SELECT draft_id FROM interview_minutes_reviews WHERE recap_id=? AND user_id=?",
                (r,n) -> r.getObject(1,UUID.class),recapId,userId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public Draft save(UUID userId, UUID id, long revision, String minutes) {
        validate(minutes);
        get(userId,id);
        if (jdbc.update("""
                UPDATE interview_minutes SET minutes=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND user_id=? AND revision=?
                """, minutes,id,userId,revision) != 1) throw conflict();
        return get(userId,id);
    }

    public Draft confirm(UUID userId, UUID id, long revision) {
        return tx.execute(s -> {
            var current = jdbc.query("SELECT revision,minutes FROM interview_minutes WHERE id=? AND user_id=? FOR UPDATE",
                    (r,n) -> new Object[]{r.getLong(1),r.getString(2)},id,userId);
            if (current.isEmpty()) throw new ResourceNotFoundException("纪要不存在或仍在整理");
            if ((Long)current.getFirst()[0] != revision) throw conflict();
            validate((String)current.getFirst()[1]);
            boolean exists=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM interview_minutes_reviews WHERE draft_id=? AND user_id=? AND revision=?)",
                    Boolean.class,id,userId,revision));
            boolean first= !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM interview_minutes_reviews WHERE draft_id=? AND user_id=?)",
                    Boolean.class,id,userId));
            // Upload already paid for the first complete draft-and-review workflow.
            if (!exists && !first) quotas.consume(userId,com.projectardor.usage.UsageFeature.INTERVIEW_RECAP,
                    chargeId("revision:"+id+":"+revision));
            jdbc.update("""
                    INSERT INTO interview_minutes_reviews(id,draft_id,user_id,revision,minutes,status)
                    VALUES (?,?,?,?,?,'QUEUED') ON CONFLICT(draft_id,revision) DO NOTHING
                    """,UUID.randomUUID(),id,userId,revision,current.getFirst()[1]);
            return get(userId,id);
        });
    }

    public Draft retryReview(UUID userId, UUID id, UUID reviewId) {
        return tx.execute(s -> {
        get(userId,id);
        var attempts=jdbc.query("SELECT attempts FROM interview_minutes_reviews WHERE id=? AND draft_id=? AND user_id=? AND status='FAILED' FOR UPDATE",
                (r,n) -> r.getInt(1),reviewId,id,userId);
        if (attempts.isEmpty()) throw new ResourceNotFoundException("可重试的复盘任务不存在");
        quotas.consume(userId,com.projectardor.usage.UsageFeature.INTERVIEW_RECAP,chargeId("retry:"+reviewId+":"+attempts.getFirst()));
        if (jdbc.update("""
                UPDATE interview_minutes_reviews SET status='QUEUED',error_message=NULL,started_at=NULL
                WHERE id=? AND draft_id=? AND user_id=? AND status='FAILED'
                """,reviewId,id,userId) != 1) throw conflict();
        return get(userId,id);
        });
    }

    public void retryReview(UUID userId, UUID reviewId) {
        var ids=jdbc.query("SELECT draft_id FROM interview_minutes_reviews WHERE id=? AND user_id=?",
                (r,n) -> r.getObject(1,UUID.class),reviewId,userId);
        if (ids.isEmpty()) throw new ResourceNotFoundException("复盘任务不存在");
        retryReview(userId,ids.getFirst(),reviewId);
    }

    public ReviewWork claim() {
        return tx.execute(s -> {
            jdbc.update("""
                    UPDATE interview_minutes_reviews SET status='FAILED',error_message='后台中断，请重试',finished_at=CURRENT_TIMESTAMP
                    WHERE status='RUNNING' AND started_at < CURRENT_TIMESTAMP - INTERVAL '20 minutes'
                    """);
            var work = jdbc.query("""
                    WITH next AS (SELECT id FROM interview_minutes_reviews WHERE status='QUEUED'
                        ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                    UPDATE interview_minutes_reviews r SET status='RUNNING',attempts=attempts+1,started_at=CURRENT_TIMESTAMP
                    FROM next WHERE r.id=next.id RETURNING r.*
                    """,(r,n) -> new ReviewWork(r.getObject("id",UUID.class),r.getObject("draft_id",UUID.class),
                        r.getObject("user_id",UUID.class),r.getString("minutes"),r.getInt("attempts")));
            return work.isEmpty() ? null : work.getFirst();
        });
    }

    public UUID opportunity(ReviewWork work) {
        return jdbc.queryForObject("SELECT job_opportunity_id FROM interview_recap_jobs WHERE id=? AND user_id=?",
                UUID.class,work.draftId(),work.userId());
    }

    public com.projectardor.opportunity.service.InterviewContextAccess.Context context(ReviewWork work) {
        return jdbc.queryForObject("SELECT job_opportunity_id,interview_round_id FROM interview_recap_jobs WHERE id=? AND user_id=?",
                (r,n) -> new com.projectardor.opportunity.service.InterviewContextAccess.Context(
                    r.getObject("job_opportunity_id",UUID.class), r.getObject("interview_round_id",UUID.class)),work.draftId(),work.userId());
    }

    public void finish(ReviewWork work, UUID recapId, boolean failed) {
        tx.executeWithoutResult(s -> {
        int changed=jdbc.update("""
                UPDATE interview_minutes_reviews SET status=?,recap_id=?,error_message=?,finished_at=CURRENT_TIMESTAMP
                WHERE id=? AND user_id=? AND status='RUNNING' AND attempts=?
                """,failed ? "FAILED" : "COMPLETED",recapId,failed ? "复盘未完成，可重试；原始转录和已确认纪要已保留。" : null,
                work.id(),work.userId(),work.attempt());
        if (!failed && changed==1) jdbc.update("""
                UPDATE interview_recap_jobs j SET recap_id=? WHERE id=? AND user_id=?
                AND NOT EXISTS(SELECT 1 FROM interview_minutes_reviews newer
                    JOIN interview_minutes_reviews own ON own.id=? AND own.user_id=j.user_id
                    WHERE newer.draft_id=j.id AND newer.status='COMPLETED' AND newer.revision>own.revision)
                """,recapId,work.draftId(),work.userId(),work.id());
        });
    }

    static void validate(String text) {
        if (text == null || text.strip().length() < 30 || text.length() > 50000)
            throw new IllegalArgumentException("纪要须为 30–50000 个字符，不会自动截断");
    }
    private static UUID chargeId(String value) { return UUID.nameUUIDFromBytes(("interview-minutes:"+value).getBytes(StandardCharsets.UTF_8)); }
    static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"纪要已更新，请重新读取；本地输入不会自动覆盖"); }
    public static List<String> chunks(String text, int size) {
        List<String> parts = new ArrayList<>();
        for (int from=0; from<text.length();) {
            int end=Math.min(text.length(),from+size);
            if (end<text.length()) {
                int line=text.lastIndexOf('\n',end);
                if (line>from+size/2) end=line+1;
                if (end<text.length() && Character.isHighSurrogate(text.charAt(end-1))) end--;
            }
            parts.add(text.substring(from,end)); from=end;
        }
        return parts;
    }
    public record DraftSummary(UUID id,String company,String status,long revision) {}
    public record Draft(UUID id,String rawContent,String minutes,long revision,String status,String errorMessage,List<Review> reviews) {}
    public record Review(UUID id,long revision,String status,UUID recapId,String errorMessage,String minutes) {}
    public record ReviewWork(UUID id,UUID draftId,UUID userId,String minutes,int attempt) {}
}
