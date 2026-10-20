package com.projectardor.recap.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.recap.repository.MemoryCardRepository;
import com.projectardor.recap.repository.InterviewReplayAttemptRepository;

/** Practice/review-owned projection. No answer text, card back or model evaluation body. */
@Service
public class QuestionPracticeProgressAccess {
    private final MemoryCardRepository cards;
    private final InterviewReplayAttemptRepository replays;
    public QuestionPracticeProgressAccess(MemoryCardRepository cards, InterviewReplayAttemptRepository replays) {
        this.cards=cards;this.replays=replays;
    }
    @Transactional(readOnly=true)
    public Snapshot snapshot(UUID owner, Collection<UUID> questionIds) {
        var linkedCards=new LinkedHashMap<UUID,List<Card>>();
        var linkedReplay=new LinkedHashMap<UUID,Replay>();
        if(!questionIds.isEmpty()) {
            for(var card:cards.findAllByUserIdAndRecapQuestionIdInOrderByCreatedAtDesc(owner,questionIds)) {
                linkedCards.computeIfAbsent(card.getRecapQuestionId(),ignored -> new ArrayList<>()).add(
                        new Card(card.getId(),card.getStatus().name(),card.getRepetitions(),card.getNextReviewAt()));
            }
            for(var attempt:replays.progressReplays(owner,questionIds)) {
                linkedReplay.put(attempt.getRecapQuestionId(),new Replay(attempt.getAttemptCount(),
                        attempt.getLatestAttemptId(),attempt.getLatestVerdict(),attempt.getLatestAt()));
            }
        }
        return new Snapshot(cards.countByUserId(owner),replays.countByUserId(owner),linkedCards,linkedReplay);
    }
    public record Card(UUID id, String status, int repetitions, Instant nextReviewAt) {}
    public record Replay(long attemptCount, UUID latestAttemptId, String latestVerdict, Instant latestAt) {}
    public record Snapshot(long totalCards,long totalReplayAttempts,Map<UUID,List<Card>> cards,Map<UUID,Replay> replays) {
        public Snapshot {
            var copy=new LinkedHashMap<UUID,List<Card>>();cards.forEach((id,values) -> copy.put(id,List.copyOf(values)));
            cards=Map.copyOf(copy);replays=Map.copyOf(replays);
        }
    }
}
