package com.projectardor.scheduling.advice.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_advice")
public class CalendarAdvice {
    @Id @Column(name = "user_id") private UUID userId;
    /** Legacy free-text columns; still read and normalised for old rows. */
    @Column(columnDefinition = "text") private String days;
    @Column(columnDefinition = "text") private String weeks;
    @Column(columnDefinition = "text") private String months;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private CalendarAdvicePayload payload;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected CalendarAdvice() {}

    public static CalendarAdvice create(UUID userId, CalendarAdvicePayload payload) {
        CalendarAdvice advice = new CalendarAdvice();
        advice.userId = userId;
        advice.update(payload);
        return advice;
    }

    public void update(CalendarAdvicePayload payload) {
        this.payload = payload;
        this.updatedAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public String getDays() { return days; }
    public String getWeeks() { return weeks; }
    public String getMonths() { return months; }
    public CalendarAdvicePayload getPayload() { return payload; }
    public Instant getUpdatedAt() { return updatedAt; }
}
