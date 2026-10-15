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

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from TaskSeries s where s.id=:id and s.userId=:userId")
    Optional<TaskSeries> findOwnedForUpdate(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("userId") UUID userId);

    interface ExpansionCandidate { UUID getId(); UUID getUserId(); }
    @org.springframework.data.jpa.repository.Query("select s.id as id, s.userId as userId from TaskSeries s where s.status=:status and s.materializedThrough<:horizon order by s.materializedThrough asc")
    List<ExpansionCandidate> findExpansionCandidates(@org.springframework.data.repository.query.Param("status") TaskSeriesStatus status,
            @org.springframework.data.repository.query.Param("horizon") LocalDate horizon, Limit limit);

    List<TaskSeries> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    List<TaskSeries> findAllByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, TaskSeriesStatus status);

    /** Series whose expansion has fallen behind the horizon, oldest window first. */
    List<TaskSeries> findAllByStatusAndMaterializedThroughLessThanOrderByMaterializedThroughAsc(
            TaskSeriesStatus status, LocalDate horizon, Limit limit);
}
