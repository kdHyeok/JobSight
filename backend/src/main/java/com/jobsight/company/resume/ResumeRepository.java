package com.jobsight.company.resume;

import com.jobsight.company.resume.dto.ResumePositionRow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {

    /** 소유자 조건을 쿼리에 넣어 남의 행은 애초에 조회되지 않게 한다. findById 후 비교 금지. */
    Optional<Resume> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** PostgreSQL 행 잠금 대기 상한을 이 트랜잭션에만 적용한다. 커밋/롤백 때 자동 복원된다. */
    @Modifying
    @Query(value = "set local lock_timeout = '3s'", nativeQuery = true)
    void setLocalLockTimeout();

    /** 같은 이력서에 동시에 들어온 쓰기가 서로를 덮지 않게 행을 잠근다 — docs/resumes.md 불변 조건 10. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Resume r where r.id = :id and r.ownerId = :ownerId")
    Optional<Resume> findOwnedForUpdate(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    List<Resume> findAllByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

    List<Resume> findAllByIdInAndOwnerId(List<UUID> ids, UUID ownerId);

    /** 이름과 지원 직무만 이력서×직무 한 줄씩 읽는다(직무가 없으면 null 한 줄). content JSONB 를 읽지 않는다 — 설계 1.3 P4. */
    @Query("""
            select new com.jobsight.company.resume.dto.ResumePositionRow(r.id, r.name, p)
              from Resume r left join r.positionIds p
             where r.id in :ids and r.ownerId = :ownerId
            """)
    List<ResumePositionRow> findPositionRows(@Param("ids") Collection<UUID> ids, @Param("ownerId") UUID ownerId);
}
