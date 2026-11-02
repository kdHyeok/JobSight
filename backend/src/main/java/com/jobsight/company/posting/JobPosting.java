package com.jobsight.company.posting;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "job_postings")
public class JobPosting {
    @Id
    private UUID id;

    /** 계정별 데이터 분리의 기준. 기업과 같은 규칙이다. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    /** 항상 있다. 직접 입력한 회사는 서비스가 찾거나 만든다. */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** 모집 부문. "2026 상반기 신입 공채" 또는 단일 직무 공고면 그 직무 이름. */
    @Column(nullable = false, length = 160)
    private String title;

    @Column(name = "posting_url", length = 500)
    private String postingUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false, length = 20)
    private EmploymentType employmentType;

    /** NULL 이면 상시채용. 정렬에서 맨 뒤로 간다. */
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    /** 내 참여 상태. 회사 절차 진행은 steps 가 가진다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApplicationStatus status;

    @Column(columnDefinition = "TEXT")
    private String qualifications;

    @Column(columnDefinition = "TEXT")
    private String memo;

    /** 공채에서 내가 실제로 지원한 직무. 선택. */
    @Column(name = "target_position_id")
    private UUID targetPositionId;

    /** 채용 절차. 순서는 seq 컬럼이 관리한다. 공고와 함께 저장·삭제된다. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "posting_id", nullable = false)
    @OrderColumn(name = "seq", nullable = false)
    private List<RecruitmentStep> steps = new ArrayList<>();

    /** NULL 이 아니면 보관함. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JobPosting() {
    }

    public JobPosting(UUID ownerId, JobPostingAttributes attributes) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        apply(attributes);
        if (status.isRejectedOrClosed()) archivedAt = Instant.now();
    }

    public void update(JobPostingAttributes attributes) {
        boolean wasTerminal = status.isRejectedOrClosed();
        apply(attributes);
        if (status.isRejectedOrClosed()) archive(Instant.now());
        else if (wasTerminal) restore();
        this.updatedAt = Instant.now();
    }

    private void apply(JobPostingAttributes attributes) {
        this.companyId = attributes.companyId();
        this.title = attributes.title();
        this.postingUrl = attributes.postingUrl();
        this.employmentType = attributes.employmentType();
        this.deadlineAt = attributes.deadlineAt();
        this.status = attributes.status();
        this.qualifications = attributes.qualifications();
        this.memo = attributes.memo();
        if (attributes.steps() != null) {
            replaceSteps(attributes.steps());
        }
    }

    /** 같은 seq는 갱신하고 끝에서만 추가·삭제해 (posting_id, seq) flush 충돌을 피한다. */
    private void replaceSteps(List<RecruitmentStep> replacements) {
        while (steps.size() > replacements.size()) steps.remove(steps.size() - 1);
        for (int i = 0; i < replacements.size(); i++) {
            if (i < steps.size()) steps.get(i).updateFrom(replacements.get(i));
            else steps.add(replacements.get(i));
        }
    }

    public void changeStatus(ApplicationStatus status) {
        this.status = status;
        if (status.isRejectedOrClosed()) archive(Instant.now());
        else restore();
        this.updatedAt = Instant.now();
    }

    public void changeStepResult(int seq, StepResult result) {
        if (seq < 0 || seq >= steps.size()) {
            throw new IndexOutOfBoundsException("절차 단계가 없습니다: " + seq);
        }
        steps.get(seq).changeResult(result);
        this.updatedAt = Instant.now();
    }

    public void setTargetPositionId(UUID targetPositionId) {
        this.targetPositionId = targetPositionId;
    }

    public void archive(Instant at) {
        this.archivedAt = at;
        this.updatedAt = at;
    }

    public void restore() {
        this.archivedAt = null;
        this.updatedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** 탈락·종료이거나 마감이 지났고 아직 내지 않은 공고인가. */
    public boolean shouldAutoArchive(Instant now) {
        return !isArchived()
                && (status.isRejectedOrClosed()
                    || (deadlineAt != null && deadlineAt.isBefore(now) && status.isAutoArchivable()));
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
    public UUID getCompanyId() { return companyId; }
    public String getTitle() { return title; }
    public String getPostingUrl() { return postingUrl; }
    public EmploymentType getEmploymentType() { return employmentType; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public ApplicationStatus getStatus() { return status; }
    public String getQualifications() { return qualifications; }
    public String getMemo() { return memo; }
    public UUID getTargetPositionId() { return targetPositionId; }
    public List<RecruitmentStep> getSteps() { return steps; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
