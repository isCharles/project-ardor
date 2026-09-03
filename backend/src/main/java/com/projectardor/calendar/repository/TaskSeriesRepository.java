package com.projectardor.calendar.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.calendar.domain.TaskSeries;
import com.projectardor.calendar.domain.TaskSeriesStatus;

public interface TaskSeriesRepository extends JpaRepository<TaskSeries, UUID> {
    Optional<TaskSeries> findByIdAndUserId(UUID id, UUID userId);

    List<TaskSeries> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    List<TaskSeries> findAllByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, TaskSeriesStatus status);

    /** Series whose expansion has fallen behind the horizon, oldest window first. */
    List<TaskSeries> findAllByStatusAndMaterializedThroughLessThanOrderByMaterializedThroughAsc(
            TaskSeriesStatus status, LocalDate horizon, Limit limit);
}
