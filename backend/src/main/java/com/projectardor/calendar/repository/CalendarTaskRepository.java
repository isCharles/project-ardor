package com.projectardor.calendar.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.calendar.domain.CalendarTask;
import com.projectardor.calendar.domain.CalendarTaskKind;

public interface CalendarTaskRepository extends JpaRepository<CalendarTask, UUID> {
    Optional<CalendarTask> findByIdAndUserId(UUID id, UUID userId);
    Optional<CalendarTask> findByMemoryCardIdAndUserId(UUID memoryCardId, UUID userId);
    Optional<CalendarTask> findByUserIdAndTaskKindAndReviewDate(UUID userId, CalendarTaskKind taskKind, LocalDate reviewDate);
    List<CalendarTask> findAllByUserIdOrderByDueAtAscCreatedAtDesc(UUID userId);
}
