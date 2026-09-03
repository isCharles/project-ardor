package com.projectardor.calendar.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "tasks")
public class CalendarTask {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "source_interview_id") private UUID sourceInterviewId;
    @Column(name = "memory_card_id") private UUID memoryCardId;
    @Column(name = "series_id") private UUID seriesId;
    @Column(name = "occurrence_date") private LocalDate occurrenceDate;
    @Column(name = "user_modified", nullable = false) private boolean userModified;
    @Column(nullable = false, length = 240) private String title;
    @Column(columnDefinition = "text") private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private CalendarTaskStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private CalendarTaskPriority priority;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private CalendarTaskSource source;
    @Enumerated(EnumType.STRING) @Column(name = "task_kind", nullable = false, length = 32) private CalendarTaskKind taskKind;
    @Column(name = "review_date") private LocalDate reviewDate;
    @Column(name = "action_path", length = 512) private String actionPath;
    @Column(name = "due_at") private Instant dueAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected CalendarTask() {}

    private CalendarTask(
            UUID userId,
            String title,
            String description,
            Instant dueAt,
            CalendarTaskPriority priority,
            CalendarTaskSource source) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.title = title;
        this.description = description;
        this.dueAt = dueAt;
        this.priority = priority;
        this.source = source;
        this.taskKind = CalendarTaskKind.GENERAL;
        this.status = CalendarTaskStatus.TODO;
    }

    public static CalendarTask create(
            UUID userId,
            String title,
            String description,
            Instant dueAt,
            CalendarTaskPriority priority,
            CalendarTaskSource source) {
        return new CalendarTask(userId, title, description, dueAt, priority, source);
    }

    public static CalendarTask createMemoryReview(
            UUID userId, LocalDate reviewDate, int cardCount, Instant dueAt) {
        CalendarTask task = new CalendarTask(userId, "记忆卡复习", cardCount + " 张待复习", dueAt,
                CalendarTaskPriority.HIGH, CalendarTaskSource.AGENT);
        task.taskKind = CalendarTaskKind.MEMORY_REVIEW;
        task.reviewDate = reviewDate;
        task.actionPath = "/app/cards";
        return task;
    }

    /** One dated occurrence of a repeating series; an ordinary task otherwise. */
    public static CalendarTask createOccurrence(TaskSeries series, LocalDate occurrenceDate) {
        CalendarTask task = new CalendarTask(
                series.getUserId(), series.getTitle(), series.getDescription(),
                series.dueAtOn(occurrenceDate), series.getPriority(), series.getSource());
        task.seriesId = series.getId();
        task.occurrenceDate = occurrenceDate;
        return task;
    }

    public void scheduleMemoryCardReview(int cardCount, Instant dueAt) {
        this.title = "记忆卡复习";
        this.description = cardCount + " 张待复习";
        this.dueAt = dueAt;
        this.priority = CalendarTaskPriority.HIGH;
        this.status = CalendarTaskStatus.TODO;
        this.completedAt = null;
    }

    public void update(
            String title,
            String description,
            Instant dueAt,
            CalendarTaskPriority priority,
            CalendarTaskStatus status) {
        this.title = title;
        this.description = description;
        this.dueAt = dueAt;
        this.priority = priority;
        if (this.status != status) {
            this.status = status;
            this.completedAt = status == CalendarTaskStatus.COMPLETED ? Instant.now() : null;
        }
        this.userModified = true;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getMemoryCardId() { return memoryCardId; }
    public UUID getSeriesId() { return seriesId; }
    public LocalDate getOccurrenceDate() { return occurrenceDate; }
    public boolean isUserModified() { return userModified; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public CalendarTaskStatus getStatus() { return status; }
    public CalendarTaskPriority getPriority() { return priority; }
    public CalendarTaskSource getSource() { return source; }
    public CalendarTaskKind getTaskKind() { return taskKind; }
    public LocalDate getReviewDate() { return reviewDate; }
    public String getActionPath() { return actionPath; }
    public Instant getDueAt() { return dueAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
