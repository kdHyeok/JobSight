package com.jobsight.company.company;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.company.dto.CompanyRequest;
import com.jobsight.company.company.dto.CompanyResponse;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.posting.JobPostingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 모든 조회·변경이 현재 로그인 계정의 소유 데이터로 한정된다.
 * 소유자 id 를 컨트롤러에서 받지 않고 여기서 직접 얻으므로 스코프 누락이 발생할 수 없다.
 */
@Service
@Transactional(readOnly = true)
public class CompanyService {
    private final CompanyRepository repository;
    private final JobPostingService postings;
    private final PositionRepository positionRepository;
    private final CurrentUser currentUser;

    public CompanyService(CompanyRepository repository, JobPostingService postings, PositionRepository positionRepository,
                          CurrentUser currentUser) {
        this.repository = repository;
        this.postings = postings;
        this.positionRepository = positionRepository;
        this.currentUser = currentUser;
    }

    /** 목록에는 공고를 담지 않는다. 기업마다 공고를 조회하면 N+1 이 된다. */
    public List<CompanyResponse> findAll() {
        return repository.findAllByOwnerIdOrderByUpdatedAtDesc(currentUser.id()).stream()
                .map(CompanyResponse::summaryOf)
                .toList();
    }

    /**
     * 상세에는 마감이 지나지 않은 공고를 채운다. 사용자가 입력하는 값이 아니다.
     * 이 기업을 지우면 소속 공고(마감 지난 것 포함) 직무의 직접 출처가 사라질 자소서 수도 싣는다(삭제 확인창용).
     */
    public CompanyResponse findById(UUID id) {
        Company company = findOwnedEntity(id);
        return CompanyResponse.of(company, postings.findOpenByCompany(company.getId()))
                .withLinkedSelfIntroCount(positionRepository.countSelfIntrosWrittenForCompany(id, currentUser.id()));
    }

    @Transactional
    public CompanyResponse create(CompanyRequest request) {
        Company company = new Company(currentUser.id(), toAttributes(request));
        return CompanyResponse.summaryOf(repository.save(company));
    }

    @Transactional
    public CompanyResponse update(UUID id, CompanyRequest request) {
        Company company = findOwnedEntity(id);
        company.update(toAttributes(request));
        Company saved = repository.save(company);
        return CompanyResponse.of(saved, postings.findOpenByCompany(saved.getId()));
    }

    @Transactional
    public void delete(UUID id) {
        // 연결된 공고도 함께 지워진다(job_postings.company_id ON DELETE CASCADE).
        repository.delete(findOwnedEntity(id));
    }

    /** 남의 데이터는 존재 자체를 알리지 않도록 403 이 아니라 404 로 응답한다. */
    private Company findOwnedEntity(UUID id) {
        return repository.findByIdAndOwnerId(id, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    private CompanyAttributes toAttributes(CompanyRequest request) {
        return new CompanyAttributes(
                request.name().trim(),
                normalize(request.websiteUrl()),
                normalizeIndustries(request.industries()),
                normalizeBusinesses(request.businesses()),
                request.companySize(),
                request.annualRevenue(),
                request.annualRevenue() == null ? null : revenueUnit(request),
                request.employeeCount(),
                normalize(request.address()),
                request.foundedOn(),
                normalize(request.summary()),
                normalize(request.benefits()),
                normalize(request.memo())
        );
    }

    /** 사업명이 빈 행은 버린다 — 폼에서 빈 줄을 남기고 저장하는 일이 흔하다. 순서는 유지한다. */
    private static List<com.jobsight.company.company.BusinessArea> normalizeBusinesses(
            List<com.jobsight.company.company.dto.BusinessAreaRequest> raw) {
        if (raw == null) {
            return List.of();
        }
        List<com.jobsight.company.company.BusinessArea> areas = new ArrayList<>();
        for (var area : raw) {
            String name = normalize(area.name());
            if (name != null) {
                areas.add(new com.jobsight.company.company.BusinessArea(name, normalize(area.description())));
            }
        }
        return areas;
    }

    /** 빈 값과 중복을 걸러내고 입력 순서를 유지한다. */
    private static List<String> normalizeIndustries(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : raw) {
            String normalized = normalize(value);
            if (normalized != null) {
                unique.add(normalized);
            }
        }
        return new ArrayList<>(unique);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static RevenueUnit revenueUnit(CompanyRequest request) {
        if (request.revenueUnit() != null) return request.revenueUnit();
        return request.annualRevenue() >= 100_000_000L ? RevenueUnit.HUNDRED_MILLION : RevenueUnit.TEN_THOUSAND;
    }
}
