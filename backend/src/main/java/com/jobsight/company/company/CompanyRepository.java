package com.jobsight.company.company;

import com.jobsight.company.common.OwnerCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyRepository extends JpaRepository<Company, UUID> {

    List<Company> findAllByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

    /**
     * 소유자 조건을 쿼리에 포함시켜, 남의 행은 애초에 조회되지 않게 한다.
     * findById 후 소유자를 비교하는 방식은 비교를 빠뜨리면 그대로 유출이 되므로 쓰지 않는다.
     */
    Optional<Company> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** 소유 확인만 필요할 때. 엔티티와 EAGER 업종·주요 사업을 읽지 않는다. */
    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    /** 공고 목록에서 기업 이름을 한 번에 채우기 위한 조회(N+1 방지). */
    List<Company> findAllByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);

    long countByOwnerId(UUID ownerId);

    /** 관리자 화면의 계정별 등록 수. 개수만 낸다 — 내용은 내보내지 않는다(docs/admin.md). */
    @Query("select new com.jobsight.company.common.OwnerCount(c.ownerId, count(c)) from Company c group by c.ownerId")
    List<OwnerCount> countGroupedByOwner();

    /** 공백 제거·소문자 키로 찾는다. 공고 폼의 직접 입력이 기존 기업을 재사용하기 위해. */
    @Query("select c from Company c where c.ownerId = :ownerId and lower(replace(c.name, ' ', '')) = :key")
    Optional<Company> findByOwnerIdAndNameKey(@Param("ownerId") UUID ownerId, @Param("key") String key);
}
