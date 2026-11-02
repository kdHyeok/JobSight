package com.jobsight.company.position;

import com.jobsight.company.common.OwnerCount;
import com.jobsight.company.position.dto.PositionLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PositionRepository extends JpaRepository<Position, UUID> {

    Optional<Position> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Position> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    List<Position> findAllByPostingIdAndOwnerId(UUID postingId, UUID ownerId);

    List<Position> findAllByPostingIdInAndOwnerIdOrderByCreatedAtAsc(Collection<UUID> postingIds, UUID ownerId);

    List<Position> findAllByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);

    long countByPostingIdAndOwnerId(UUID postingId, UUID ownerId);

    /** 이름과 소속 공고·기업만 한 번에 읽는다. 직무 상세와 EAGER 컬렉션(스택·참고 정보)은 읽지 않는다. */
    @Query("""
            select new com.jobsight.company.position.dto.PositionLabel(p.id, p.name, j.id, c.id, c.name)
              from Position p
              left join JobPosting j on j.id = p.postingId and j.ownerId = p.ownerId
              left join Company c on c.id = j.companyId and c.ownerId = p.ownerId
             where p.id in :ids and p.ownerId = :ownerId
            """)
    List<PositionLabel> findLabels(@Param("ids") Collection<UUID> ids, @Param("ownerId") UUID ownerId);

    /** 공고·기업 필터의 대상 직무를 고를 때. findLabels 와 같은 모양으로 계정 전체를 읽는다. */
    @Query("""
            select new com.jobsight.company.position.dto.PositionLabel(p.id, p.name, j.id, c.id, c.name)
              from Position p
              left join JobPosting j on j.id = p.postingId and j.ownerId = p.ownerId
              left join Company c on c.id = j.companyId and c.ownerId = p.ownerId
             where p.ownerId = :ownerId
            """)
    List<PositionLabel> findAllLabels(@Param("ownerId") UUID ownerId);

    /** 관리자 화면의 계정별 등록 수. 개수만 낸다 — 내용은 내보내지 않는다(docs/admin.md). */
    @Query("select new com.jobsight.company.common.OwnerCount(p.ownerId, count(p)) from Position p group by p.ownerId")
    List<OwnerCount> countGroupedByOwner();
}
