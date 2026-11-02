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

    /** PostgreSQL 행 잠금 대기 상한을 이 트랜잭션에만 적용한다. 커밋/롤백 때 자동 복원된다. */
    @Modifying
    @Query(value = "set local lock_timeout = '3s'", nativeQuery = true)
    void setLocalLockTimeout();

    /** 같은 문항에 동시에 들어온 쓰기(웹 PUT·MCP·확정)가 서로를 덮지 않게 행을 잠근다 — docs/self-introductions.md 불변 조건 12. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SelfIntroduction s where s.id = :id and s.ownerId = :ownerId")
    Optional<SelfIntroduction> findOwnedForUpdate(@Param("id") UUID id, @Param("ownerId") UUID ownerId);
}
