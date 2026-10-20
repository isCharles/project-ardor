package com.projectardor.learning.review.service;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.learning.review.repository.MemoryCardRepository;

/** Review-owned summary; never transports card front/back text. */
@Service
public class FlashcardProgressAccess {
    private final MemoryCardRepository cards;
    public FlashcardProgressAccess(MemoryCardRepository cards){this.cards=cards;}
    @Transactional(readOnly=true)
    public Snapshot snapshot(UUID owner,Collection<UUID> questionIds) {
        var linked=new LinkedHashMap<UUID,List<Card>>();
        if(!questionIds.isEmpty()) for(var card:cards.findAllByUserIdAndRecapQuestionIdInOrderByCreatedAtDesc(owner,questionIds))
            linked.computeIfAbsent(card.getRecapQuestionId(),ignored->new ArrayList<>()).add(
                    new Card(card.getId(),card.getStatus().name(),card.getRepetitions(),card.getNextReviewAt()));
        return new Snapshot(cards.countByUserId(owner),linked);
    }
    public record Card(UUID id,String status,int repetitions,Instant nextReviewAt) {}
    public record Snapshot(long totalCards,Map<UUID,List<Card>> cards) {
        public Snapshot {
            var copy=new LinkedHashMap<UUID,List<Card>>();cards.forEach((id,values)->copy.put(id,List.copyOf(values)));
            cards=Map.copyOf(copy);
        }
    }
}
