package com.jobsight.company.experience;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 경험 원본 — 사실(facts)과 소재집(dossier)을 한 레코드에 둔다(설계 6.5, docs/experiences.md).
 * facts·dossier 는 JSON 문자열이고 모양은 ExperienceFacts·ExperienceDossier 다(변환은 서비스가 한다 — 이력서와 같은 방식).
 * id 를 생성자에서 채우고 version 이 primitive 라 Spring Data 가 새 행을 알아보지 못해 save 가 merge(+SELECT)가 된다.
 * 가져오기의 대량 insert 를 위해 Persistable#isNew 를 직접 답한다(설계 2.5, R48).
 */
@Entity
@Table(name = "experiences")
public class Experience implements Persistable<UUID> {
    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExperienceKind kind;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "title_key", nullable = false, length = 200)
    private String titleKey;

    @Column(name = "start_ym", length = 200)
    private String startYm;

    @Column(length = 2000)
    private String memo;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> skills = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> domains = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String facts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String dossier;

    @Column(nullable = false)
    private int revision = 1;

    @Column(name = "dossier_based_on")
    private Integer dossierBasedOn;

    @Column(name = "reviewed_revision")
    private Integer reviewedRevision;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "embedding_dirty", nullable = false)
    private boolean embeddingDirty = true;

    @Column(name = "source_revision", nullable = false)
    private long sourceRevision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean fresh;

    protected Experience() {
    }

    public Experience(UUID ownerId, ExperienceKind kind, String title, String startYm, String factsJson) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.facts = factsJson;
        this.fresh = true;
        rename(kind, title, startYm);
    }

    void rename(ExperienceKind kind, String title, String startYm) {
        this.kind = kind;
        this.title = title.trim();
        this.titleKey = KeyNormalizer.key(this.title);
        this.startYm = startYm == null || startYm.isBlank() ? null : startYm.trim();
    }

    void describe(String memo, List<String> skills, List<String> domains) {
        this.memo = memo;
        this.skills = List.copyOf(skills);
        this.domains = List.copyOf(domains);
    }

    /** 사실이 바뀌어 개정이 오른다. 소재집은 손대지 않으면 예전 개정 기준으로 남는다(dossierBasedOn). */
    void revise(String factsJson) {
        this.facts = factsJson;
        this.revision++;
    }

    void writeDossier(String dossierJson) {
        this.dossier = dossierJson;
        this.dossierBasedOn = dossierJson == null ? null : revision;
    }

    void markReviewed() {
        this.reviewedRevision = revision;
    }

    /** 검색 텍스트가 바뀌었다 — 5단계 스위퍼가 다시 계산한다(4.4). 4단계는 표시만 한다. */
    void touchSearchText() {
        this.embeddingDirty = true;
        this.sourceRevision++;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        fresh = false;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public ExperienceKind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getTitleKey() { return titleKey; }
    public String getStartYm() { return startYm; }
    public String getMemo() { return memo; }
    public List<String> getSkills() { return skills; }
    public List<String> getDomains() { return domains; }
    public String getFacts() { return facts; }
    public String getDossier() { return dossier; }
    public int getRevision() { return revision; }
    public Integer getDossierBasedOn() { return dossierBasedOn; }
    public Integer getReviewedRevision() { return reviewedRevision; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** 사용자가 지금 개정까지 검토했나. 아니면 'AI 기록'. */
    public boolean isReviewed() {
        return reviewedRevision != null && reviewedRevision == revision;
    }
}
