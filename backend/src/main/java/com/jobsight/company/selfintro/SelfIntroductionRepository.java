package com.jobsight.company.selfintro;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SelfIntroductionRepository extends JpaRepository<SelfIntroduction, UUID> {
    Optional<SelfIntroduction> findByIdAndOwnerId(UUID id, UUID ownerId);
    List<SelfIntroduction> findAllByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

    /**
     * 이 이력서에 연결된 내 자소서만 — 설계 1.3 P3. 소유자 전체를 읽고 자바에서 거르지 않는다.
     * 연결 표의 idx_self_introduction_resumes_resume(V20)을 쓸 수 있다. 문항당 같은 이력서는 한 번뿐이라 중복이 없다.
     */
    @Query("""
            select s from SelfIntroduction s join s.resumeIds r
             where s.ownerId = :ownerId and r = :resumeId
             order by s.updatedAt desc
            """)
    List<SelfIntroduction> findAllLinkedToResume(@Param("ownerId") UUID ownerId, @Param("resumeId") UUID resumeId);

    /** 직접 출처(written_for)가 없는 내 문항 수 — experience_index counts(설계 5.2). 소유자 조건을 양쪽에 건다. */
    @Query(value = """
            select count(*) from self_introductions s
             where s.owner_id = :ownerId
               and not exists (select 1 from self_introduction_positions p
                                where p.self_introduction_id = s.id and p.owner_id = :ownerId)
            """, nativeQuery = true)
    long countWithoutDirectSource(@Param("ownerId") UUID ownerId);

    /** PostgreSQL 행 잠금 대기 상한을 이 트랜잭션에만 적용한다. 커밋/롤백 때 자동 복원된다. */
    @Modifying
    @Query(value = "set local lock_timeout = '3s'", nativeQuery = true)
    void setLocalLockTimeout();

    /** 같은 문항에 동시에 들어온 쓰기(웹 PUT·MCP·확정)가 서로를 덮지 않게 행을 잠근다 — docs/self-introductions.md 불변 조건 12. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SelfIntroduction s where s.id = :id and s.ownerId = :ownerId")
    Optional<SelfIntroduction> findOwnedForUpdate(@Param("id") UUID id, @Param("ownerId") UUID ownerId);
}
