package com.projectardor.learning.review.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.learning.review.domain.*;
import com.projectardor.learning.review.web.MemoryCardResponse;
import tools.jackson.databind.json.JsonMapper;

class FlashcardApplicationContractTests {
    @Test void ownedViewPreservesEveryLegacyHttpAndToolField() {
        UUID id=UUID.randomUUID(),question=UUID.randomUUID();var time=Instant.parse("2026-10-20T01:00:00Z");
        var tags=new ArrayList<>(List.of("tag"));var factor=new BigDecimal("2.50");
        var view=new FlashcardView(id,question,MemoryCardSource.INTERVIEW,"Fixture",null,"Question","Answer",tags,MemoryCardStatus.REVIEW,time,3,factor,2,1,time);
        var legacy=new MemoryCardResponse(id,question,MemoryCardSource.INTERVIEW,"Fixture",null,"Question","Answer",List.of("tag"),MemoryCardStatus.REVIEW,time,3,factor,2,1,time);
        var mapper=JsonMapper.builder().build();
        assertThat(mapper.writeValueAsString(view)).isEqualTo(mapper.writeValueAsString(legacy));
        assertThat(mapper.writeValueAsString(MemoryCardResponse.from(view))).isEqualTo(mapper.writeValueAsString(legacy));
        assertThat(mapper.readTree(mapper.writeValueAsString(view)).propertyNames()).containsExactly("id","recapQuestionId","sourceType","sourceLabel","sourceUrl","front","back","tags","status","nextReviewAt","intervalDays","easeFactor","repetitions","lapses","createdAt");
        tags.clear();assertThat(view.tags()).containsExactly("tag");
        assertThatThrownBy(()->view.tags().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void movingOwnershipDoesNotRenameJpaEntitiesOrTables() {
        assertThat(MemoryCard.class.getAnnotation(jakarta.persistence.Table.class).name()).isEqualTo("memory_cards");
        assertThat(MemoryCardReview.class.getAnnotation(jakarta.persistence.Table.class).name()).isEqualTo("memory_card_reviews");
        assertThat(MemoryCard.class.getSimpleName()).isEqualTo("MemoryCard");
        assertThat(MemoryCardReview.class.getSimpleName()).isEqualTo("MemoryCardReview");
    }
    @Test void reviewOwnsStorageAndRecordServiceCannotReachIt() throws Exception {
        var root=Path.of("src/main/java/com/projectardor");
        var record=Files.readString(root.resolve("jobsearch/record/service/InterviewRecapService.java"),StandardCharsets.UTF_8);
        assertThat(record).doesNotContain("MemoryCard","Flashcard","CalendarTaskService","ProfileService");
        var review=Files.readString(root.resolve("learning/review/service/FlashcardService.java"),StandardCharsets.UTF_8);
        assertThat(review).doesNotContain("recap.repository","recap.web","agent.web");
        var tools=Files.readString(root.resolve("agent/tools/CareerAgentTools.java"),StandardCharsets.UTF_8);
        assertThat(tools).doesNotContain("recap.web.MemoryCardResponse","interviewRecapService.listCards","interviewRecapService.createCard");
        assertThat(Files.exists(root.resolve("recap"))).isFalse();
    }
}
