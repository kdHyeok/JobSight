package com.jobsight.company.resume;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 이력서 한 버전. 섹션 전체가 content JSON 문서 하나다 — docs/resumes.md.
 * JSON 변환은 서비스가 한다. 엔티티는 문자열만 든다.
 */
@Entity
@Table(name = "resumes")
public class Resume {
    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    /** 이력서 명. 유일하지 않다 — 버전은 id 로 구분한다. */
    @Column(nullable = false, length = 120)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String content;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "resume_positions", joinColumns = @JoinColumn(name = "resume_id"))
    @Column(name = "position_id", nullable = false)
    private Set<UUID> positionIds = new LinkedHashSet<>();

    /** 저장할 때마다 오른다. 예전에 읽은 문서로 덮어쓰지 않게 서비스가 비교한다 — docs/resumes.md 불변 조건 8. */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Resume() {
    }

    public Resume(UUID ownerId, String name, String contentJson) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.name = name;
        this.content = contentJson;
    }

    public void update(String name, String contentJson) {
        this.name = name;
        this.content = contentJson;
        this.updatedAt = Instant.now();
    }

    public void replacePositions(Set<UUID> positionIds) {
        this.positionIds = new LinkedHashSet<>(positionIds);
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getContent() { return content; }
    public Set<UUID> getPositionIds() { return positionIds; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
