package com.jobsight.company.posting;

import com.jobsight.company.common.OwnerCount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobPostingRepository extends JpaRepository<JobPosting, UUID> {

    /**
     * 소유자 조건을 쿼리에 포함시켜 남의 행은 애초에 조회되지 않게 한다.
     * findById 후 소유자를 비교하는 방식은 비교를 빠뜨리면 그대로 유출이 되므로 쓰지 않는다.
     */
    Optional<JobPosting> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** PostgreSQL 행 잠금 대기 상한을 이 트랜잭션에만 적용한다. 커밋/롤백 때 자동 복원된다. */
    @Modifying
    @Query(value = "set local lock_timeout = '3s'", nativeQuery = true)
    void setLocalLockTimeout();

    /** 같은 공고의 직무를 동시에 지워도 마지막 직무 판단이 엇갈리지 않게 부모 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from JobPosting p where p.id = :id and p.ownerId = :ownerId")
    Optional<JobPosting> findOwnedForUpdate(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    /** 관리자 화면의 계정별 등록 수. 개수만 낸다 — 내용은 내보내지 않는다(docs/admin.md). */
    @Query("select new com.jobsight.company.common.OwnerCount(p.ownerId, count(p)) from JobPosting p group by p.ownerId")
    List<OwnerCount> countGroupedByOwner();

    /** 직무 목록에서 공고 제목·마감을 한 번에 채우기 위한 조회(N+1 방지). */
    List<JobPosting> findAllByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);

    /** 진행 중 공고를 D-day 순으로. 마감 없는(상시) 공고는 맨 뒤. */
    @Query("""
            select p from JobPosting p
             where p.ownerId = :ownerId and p.archivedAt is null
             order by p.deadlineAt asc nulls last, p.createdAt desc
            """)
    List<JobPosting> findOpen(@Param("ownerId") UUID ownerId);

    @Query("""
            select p from JobPosting p
             where p.ownerId = :ownerId and p.archivedAt is not null
             order by p.archivedAt desc
            """)
    List<JobPosting> findArchived(@Param("ownerId") UUID ownerId);

    /** 자동 보관 대상: 탈락·종료 또는 마감이 지났고 아직 내지 않은 공고. */
    @Query("""
            select p from JobPosting p
             where p.ownerId = :ownerId
               and p.archivedAt is null
               and (p.status in (com.jobsight.company.posting.ApplicationStatus.DOCUMENT_REJECTED,
                                 com.jobsight.company.posting.ApplicationStatus.WRITTEN_TEST_REJECTED,
                                 com.jobsight.company.posting.ApplicationStatus.INTERVIEW_REJECTED,
                                 com.jobsight.company.posting.ApplicationStatus.CLOSED)
                    or (p.status in (com.jobsight.company.posting.ApplicationStatus.INTERESTED,
                                     com.jobsight.company.posting.ApplicationStatus.DRAFTING)
                        and p.deadlineAt is not null and p.deadlineAt < :now))
            """)
    List<JobPosting> findPendingAutoArchive(@Param("ownerId") UUID ownerId, @Param("now") Instant now);

    /** 기업 상세의 채용정보 카드. 마감이 지나지 않은 것만. */
    @Query("""
            select p from JobPosting p
             where p.ownerId = :ownerId
               and p.companyId = :companyId
               and p.archivedAt is null
               and (p.deadlineAt is null or p.deadlineAt >= :now)
             order by p.deadlineAt asc nulls last
            """)
    List<JobPosting> findOpenByCompany(@Param("ownerId") UUID ownerId,
                                       @Param("companyId") UUID companyId,
                                       @Param("now") Instant now);
}
