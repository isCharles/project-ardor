package com.projectardor.calendar.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.projectardor.calendar.domain.CalendarTask;
import com.projectardor.calendar.domain.CalendarTaskKind;

public interface CalendarTaskRepository extends JpaRepository<CalendarTask, UUID> {
    Optional<CalendarTask> findByIdAndUserId(UUID id, UUID userId);
    Optional<CalendarTask> findByMemoryCardIdAndUserId(UUID memoryCardId, UUID userId);
    Optional<CalendarTask> findByLearningPlanIdAndUserId(UUID learningPlanId, UUID userId);
    Optional<CalendarTask> findByUserIdAndTaskKindAndReviewDate(UUID userId, CalendarTaskKind taskKind, LocalDate reviewDate);
    List<CalendarTask> findAllByUserIdOrderByDueAtAscCreatedAtDesc(UUID userId);
    List<CalendarTask> findAllBySeriesIdAndUserId(UUID seriesId, UUID userId);

    /** The dates already materialized, so re-running expansion writes nothing new. */
    @Query("select task.occurrenceDate from CalendarTask task where task.seriesId = :seriesId")
    Set<LocalDate> findOccurrenceDates(@Param("seriesId") UUID seriesId);
}
