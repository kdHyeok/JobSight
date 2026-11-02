package com.jobsight.company.selfintro;

import com.jobsight.company.common.LinkSource;
import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.JoinColumn;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Entity
@Table(name = "self_introductions")
public class SelfIntroduction {
    /** 간선 한 줄. 키(직무 id·문항 유형)와 출처만 든다. */
    interface Link<K> {
        K key();
        LinkSource source();
    }

    /** written_for: 이 답변을 어느 지원용으로 썼나(직접 출처). owner_id 는 복합 FK 로 남의 직무 연결을 DB 가 막는 데 쓴다. */
    @Embeddable
    public record PositionLink(
            @Column(name = "owner_id", nullable = false) UUID ownerId,
            @Column(name = "position_id", nullable = false) UUID positionId,
            @Enumerated(EnumType.STRING) @Column(name = "source", nullable = false, length = 4) LinkSource source
    ) implements Link<UUID> {
        public UUID key() { return positionId; }
    }

    /** 문항 유형. 저장하지 않은 문항은 질의 때 정규식으로 추정한다(Competency.estimate). */
    @Embeddable
    public record CompetencyLink(
            @Column(name = "owner_id", nullable = false) UUID ownerId,
            @Enumerated(EnumType.STRING) @Column(name = "competency_code", nullable = false, length = 40) Competency code,
            @Enumerated(EnumType.STRING) @Column(name = "source", nullable = false, length = 4) LinkSource source
    ) implements Link<Competency> {
        public Competency key() { return code; }
    }

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @ElementCollection
    @CollectionTable(name = "self_introduction_resumes",
            joinColumns = @JoinColumn(name = "self_introduction_id"))
    @Column(name = "resume_id", nullable = false)
    private Set<UUID> resumeIds = new LinkedHashSet<>();

    @ElementCollection
    @CollectionTable(name = "self_introduction_positions",
            joinColumns = @JoinColumn(name = "self_introduction_id"))
    private Set<PositionLink> positionLinks = new LinkedHashSet<>();

    @ElementCollection
    @CollectionTable(name = "self_introduction_competencies",
            joinColumns = @JoinColumn(name = "self_introduction_id"))
    private Set<CompetencyLink> questionTypes = new LinkedHashSet<>();

    @Column(nullable = false, length = 1000)
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SelfIntroduction() {
    }

    public SelfIntroduction(UUID ownerId, Set<UUID> resumeIds, String question, String answer) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        update(resumeIds, question, answer);
    }

    public void update(Set<UUID> resumeIds, String question, String answer) {
        this.resumeIds.clear();
        this.resumeIds.addAll(resumeIds);
        this.question = question;
        this.answer = answer;
        this.updatedAt = Instant.now();
    }

    /** 직무 연결을 이 집합으로 맞춘다. 이미 있던 연결은 출처를 유지하고 새 연결만 source 로 단다. */
    public void linkPositions(Set<UUID> positionIds, LinkSource source) {
        relink(positionLinks, positionIds, id -> new PositionLink(ownerId, id, source));
    }

    public void linkQuestionTypes(Set<Competency> codes, LinkSource source) {
        relink(questionTypes, codes, code -> new CompetencyLink(ownerId, code, source));
    }

    /** 웹의 ✓: 연결된 AI 기록을 USER 로 바꾼다. 연결돼 있지 않은 대상이 하나라도 있으면 false. */
    public boolean confirm(Collection<UUID> positionIds, Collection<Competency> codes) {
        return confirm(positionLinks, positionIds, id -> new PositionLink(ownerId, id, LinkSource.USER))
                && confirm(questionTypes, codes, code -> new CompetencyLink(ownerId, code, LinkSource.USER));
    }

    private static <K, L extends Link<K>> void relink(Set<L> links, Set<K> keys, Function<K, L> create) {
        links.removeIf(link -> !keys.contains(link.key()));
        Set<K> kept = links.stream().map(Link::key).collect(Collectors.toSet());
        keys.stream().filter(key -> !kept.contains(key)).map(create).forEach(links::add);
    }

    private static <K, L extends Link<K>> boolean confirm(Set<L> links, Collection<K> keys, Function<K, L> asUser) {
        for (K key : keys) {
            L link = links.stream().filter(value -> value.key().equals(key)).findFirst().orElse(null);
            if (link == null) return false;
            if (link.source() == LinkSource.AI) {
                links.remove(link);
                links.add(asUser.apply(key));
            }
        }
        return true;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public Set<UUID> getResumeIds() { return Set.copyOf(resumeIds); }
    public Set<PositionLink> getPositionLinks() { return Set.copyOf(positionLinks); }
    public Set<CompetencyLink> getQuestionTypes() { return Set.copyOf(questionTypes); }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
