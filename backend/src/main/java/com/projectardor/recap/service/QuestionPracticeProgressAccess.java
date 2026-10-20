package com.projectardor.recap.service;

import java.time.Instant;
import java.util.Map;
import java.util.Collection;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.recap.repository.InterviewReplayAttemptRepository;

/** Practice-owned projection. No answer text or model evaluation body. */
@Service
public class QuestionPracticeProgressAccess {
    private final InterviewReplayAttemptRepository replays;
    public QuestionPracticeProgressAccess(InterviewReplayAttemptRepository replays) {
        this.replays=replays;
    }
    @Transactional(readOnly=true)
    public Snapshot snapshot(UUID owner, Collection<UUID> questionIds) {
        var linkedReplay=new LinkedHashMap<UUID,Replay>();
        if(!questionIds.isEmpty()) {
            for(var attempt:replays.progressReplays(owner,questionIds)) {
                linkedReplay.put(attempt.getRecapQuestionId(),new Replay(attempt.getAttemptCount(),
                        attempt.getLatestAttemptId(),attempt.getLatestVerdict(),attempt.getLatestAt()));
            }
        }
        return new Snapshot(replays.countByUserId(owner),linkedReplay);
    }
    public record Replay(long attemptCount, UUID latestAttemptId, String latestVerdict, Instant latestAt) {}
    public record Snapshot(long totalReplayAttempts,Map<UUID,Replay> replays) {
        public Snapshot {
            replays=Map.copyOf(replays);
        }
    }
}
