package com.projectardor.profile.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(length = 240)
    private String headline;

    @Column(nullable = false, length = 80)
    private String timezone = "Asia/Shanghai";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_roles", nullable = false, columnDefinition = "jsonb")
    private List<String> targetRoles = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> preferences = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfile() {
    }

    private UserProfile(UUID id, UUID userId, String displayName) {
        this.id = id;
        this.userId = userId;
        this.displayName = displayName;
    }

    public static UserProfile create(UUID userId, String displayName) {
        return new UserProfile(UUID.randomUUID(), userId, displayName);
    }

    public void update(String displayName, String headline, List<String> targetRoles, String timezone) {
        this.displayName = displayName;
        this.headline = headline;
        this.targetRoles = new ArrayList<>(targetRoles);
        this.timezone = timezone;
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

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getHeadline() {
        return headline;
    }

    public List<String> getTargetRoles() {
        return List.copyOf(targetRoles);
    }

    public String getTimezone() { return timezone; }
}
