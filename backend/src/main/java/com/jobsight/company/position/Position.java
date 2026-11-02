package com.jobsight.company.position;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 모집 직무. 공고 하나에 1개 이상. 공고 폼은 이름만 만들고, 나머지는 직무 페이지에서 채운다.
 * 참고 정보 연결은 id 집합(position_references)으로만 들고 있다 — ManyToMany 를 두지 않는다.
 */
@Entity
@Table(name = "positions")
public class Position {
    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "posting_id", nullable = false, updatable = false)
    private UUID postingId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 120)
    private String team;

    @Column(length = 200)
    private String role;

    @Column(columnDefinition = "TEXT")
    private String responsibilities;

    @Column(columnDefinition = "TEXT")
    private String impact;

    @Column(columnDefinition = "TEXT")
    private String growth;

    @Column(columnDefinition = "TEXT")
    private String experience;

    @Column(name = "required_skills", columnDefinition = "TEXT")
    private String requiredSkills;

    @Column(name = "preferred_skills", columnDefinition = "TEXT")
    private String preferredSkills;

    @Column(length = 60)
    private String headcount;

    @Column(name = "work_location", length = 160)
    private String workLocation;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "position_tech_stack", joinColumns = @JoinColumn(name = "position_id"))
    @Column(name = "tech", length = 60, nullable = false)
    private Set<String> techStack = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "position_references", joinColumns = @JoinColumn(name = "position_id"))
    @Column(name = "reference_id", nullable = false)
    private Set<UUID> referenceIds = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Position() {
    }

    /** 공고 폼에서 이름만으로 만든다. */
    public Position(UUID ownerId, UUID postingId, String name) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.postingId = postingId;
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }

    public void update(PositionAttributes attributes) {
        this.name = attributes.name();
        this.team = attributes.team();
        this.role = attributes.role();
        this.responsibilities = attributes.responsibilities();
        this.impact = attributes.impact();
        this.growth = attributes.growth();
        this.experience = attributes.experience();
        this.requiredSkills = attributes.requiredSkills();
        this.preferredSkills = attributes.preferredSkills();
        this.headcount = attributes.headcount();
        this.workLocation = attributes.workLocation();
        this.memo = attributes.memo();
        replace(this.techStack, attributes.techStack());
        this.updatedAt = Instant.now();
    }

    public void replaceReferences(Set<UUID> referenceIds) {
        replace(this.referenceIds, referenceIds);
        this.updatedAt = Instant.now();
    }

    /**
     * 같은 컬렉션에서 빠진 것만 빼고 새것만 더한다. 새 Set 을 대입하면 Hibernate 가 저장마다 컬렉션 표 전체를
     * DELETE·INSERT 한다(설계 1.3 P8). Resume.replacePositions 와 같은 방식이다.
     */
    private static <T> void replace(Set<T> current, java.util.Collection<T> next) {
        Set<T> wanted = new LinkedHashSet<>(next);
        current.retainAll(wanted);
        current.addAll(wanted);
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
    public UUID getPostingId() { return postingId; }
    public String getName() { return name; }
    public String getTeam() { return team; }
    public String getRole() { return role; }
    public String getResponsibilities() { return responsibilities; }
    public String getImpact() { return impact; }
    public String getGrowth() { return growth; }
    public String getExperience() { return experience; }
    public String getRequiredSkills() { return requiredSkills; }
    public String getPreferredSkills() { return preferredSkills; }
    public String getHeadcount() { return headcount; }
    public String getWorkLocation() { return workLocation; }
    public String getMemo() { return memo; }
    public Set<String> getTechStack() { return techStack; }
    public Set<UUID> getReferenceIds() { return referenceIds; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
