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

    /**
     * 이 직무를 지우면 직접 출처(written_for) 연결이 CASCADE 로 사라지는 내 자소서 수 — 삭제 확인창용(docs/self-introductions.md 함정).
     * 엔티티를 읽지 않는 count 한 문장이다. 한 문항이 여러 직무에 연결돼 있어도 한 번만 센다. 공고·기업 판은 소속 직무 전부를 센다.
     */
    @Query("""
            select count(distinct s.id) from SelfIntroduction s join s.positionLinks l
             where s.ownerId = :ownerId and l.positionId = :positionId
            """)
    long countSelfIntrosWrittenFor(@Param("positionId") UUID positionId, @Param("ownerId") UUID ownerId);

    @Query("""
            select count(distinct s.id) from SelfIntroduction s join s.positionLinks l
              join Position p on p.id = l.positionId and p.ownerId = s.ownerId
             where s.ownerId = :ownerId and p.postingId = :postingId
            """)
    long countSelfIntrosWrittenForPosting(@Param("postingId") UUID postingId, @Param("ownerId") UUID ownerId);

    @Query("""
            select count(distinct s.id) from SelfIntroduction s join s.positionLinks l
              join Position p on p.id = l.positionId and p.ownerId = s.ownerId
              join JobPosting j on j.id = p.postingId and j.ownerId = s.ownerId
             where s.ownerId = :ownerId and j.companyId = :companyId
            """)
    long countSelfIntrosWrittenForCompany(@Param("companyId") UUID companyId, @Param("ownerId") UUID ownerId);

    /** 관리자 화면의 계정별 등록 수. 개수만 낸다 — 내용은 내보내지 않는다(docs/admin.md). */
    @Query("select new com.jobsight.company.common.OwnerCount(p.ownerId, count(p)) from Position p group by p.ownerId")
    List<OwnerCount> countGroupedByOwner();
}
