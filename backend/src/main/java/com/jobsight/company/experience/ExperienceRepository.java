package com.jobsight.company.experience;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 모든 조회는 소유자 조건을 쿼리 안에 둔다 — findById 후 비교 금지(AGENTS.md 불변 조건 1). */
public interface ExperienceRepository extends JpaRepository<Experience, UUID> {

    Optional<Experience> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Experience> findAllByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);

    List<Experience> findAllByOwnerIdOrderByKindAscTitleKeyAscIdAsc(UUID ownerId);

    /** 링커·목차가 쓰는 머리만. facts·dossier(JSONB)는 읽지 않는다. */
    @Query("""
            select new com.jobsight.company.experience.ExperienceHead(e.id, e.kind, e.title, e.titleKey, e.startYm,
                   e.revision, e.reviewedRevision)
              from Experience e where e.ownerId = :ownerId
            """)
    List<ExperienceHead> findHeads(@Param("ownerId") UUID ownerId);
}
