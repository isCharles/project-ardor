package com.projectardor.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.projectardor.scheduling.domain.CalendarTaskPriority;

class CalendarTaskAgentSlotsTests {
    private static final Instant DUE = Instant.parse("2028-04-01T01:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void matchingSemanticIdentityReturnsThePersistedTask() {
        UUID user = UUID.randomUUID(), action = UUID.randomUUID(), oldRun = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        String semantic = CalendarTaskAgentSlots.semanticKey("Review", DUE, CalendarTaskPriority.HIGH, null);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(user), eq(action)))
                .thenReturn(List.of(new CalendarTaskAgentSlots.Slot(task, semantic, oldRun)));

        assertThat(new CalendarTaskAgentSlots(jdbc).existing(user, action, UUID.randomUUID(), semantic))
                .contains(task);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDifferentSemanticIdentityFromAnotherRunIsRejected() {
        UUID user = UUID.randomUUID(), action = UUID.randomUUID(), oldRun = UUID.randomUUID();
        String previous = CalendarTaskAgentSlots.semanticKey("Review", DUE, CalendarTaskPriority.HIGH, null);
        String changed = CalendarTaskAgentSlots.semanticKey("Review", DUE.plusSeconds(3600), CalendarTaskPriority.HIGH, null);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(user), eq(action)))
                .thenReturn(List.of(new CalendarTaskAgentSlots.Slot(UUID.randomUUID(), previous, oldRun)));

        assertThatThrownBy(() -> new CalendarTaskAgentSlots(jdbc).existing(user, action, UUID.randomUUID(), changed))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不同的日程");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multipleDifferentItemsInTheSameRunAreAllowed() {
        UUID user = UUID.randomUUID(), action = UUID.randomUUID(), run = UUID.randomUUID();
        String first = CalendarTaskAgentSlots.semanticKey("Review", DUE, CalendarTaskPriority.HIGH, null);
        String second = CalendarTaskAgentSlots.semanticKey("Prepare", DUE, CalendarTaskPriority.HIGH, null);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(user), eq(action)))
                .thenReturn(List.of(new CalendarTaskAgentSlots.Slot(UUID.randomUUID(), first, run)));

        assertThat(new CalendarTaskAgentSlots(jdbc).existing(user, action, run, second)).isEmpty();
    }

    @Test
    void semanticIdentityIgnoresWhitespaceAndDescriptionWordingButNotTime() {
        String first = CalendarTaskAgentSlots.semanticKey("  Review   resume ", DUE,
                CalendarTaskPriority.HIGH, null);
        String same = CalendarTaskAgentSlots.semanticKey("Review resume", DUE, CalendarTaskPriority.HIGH, null);
        String otherTime = CalendarTaskAgentSlots.semanticKey("Review resume", DUE.plusSeconds(1),
                CalendarTaskPriority.HIGH, null);
        String otherPriority = CalendarTaskAgentSlots.semanticKey("Review resume", DUE,
                CalendarTaskPriority.LOW, null);

        assertThat(first).isEqualTo(same);
        assertThat(CalendarTaskAgentSlots.callSignature("  same   text "))
                .isEqualTo(CalendarTaskAgentSlots.callSignature("same text"));
        assertThat(first).isNotEqualTo(otherTime).isNotEqualTo(otherPriority);
    }
}
