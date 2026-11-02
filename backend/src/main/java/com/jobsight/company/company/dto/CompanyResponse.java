package com.jobsight.company.company.dto;

import com.jobsight.company.company.Company;
import com.jobsight.company.company.CompanySize;
import com.jobsight.company.company.RevenueUnit;
import com.jobsight.company.posting.dto.JobPostingResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * openPostings 는 사용자가 입력하는 값이 아니라 이 기업에 연결된 공고에서 채워진다.
 * 목록 응답에서는 비어 있고(N+1 방지), 상세 조회에서만 담긴다.
 */
public record CompanyResponse(
        UUID id,
        String name,
        String websiteUrl,
        List<String> industries,
        List<BusinessAreaResponse> businesses,
        CompanySize companySize,
        Long annualRevenue,
        RevenueUnit revenueUnit,
        Integer employeeCount,
        String address,
        LocalDate foundedOn,
        String summary,
        String benefits,
        String memo,
        List<JobPostingResponse> openPostings,
        Instant createdAt,
        Instant updatedAt,
        Long linkedSelfIntroCount
) {
    public static CompanyResponse summaryOf(Company company) {
        return of(company, List.of());
    }

    public static CompanyResponse of(Company company, List<JobPostingResponse> openPostings) {
        return new CompanyResponse(
                company.getId(),
                company.getName(),
                company.getWebsiteUrl(),
                List.copyOf(company.getIndustries()),
                company.getBusinesses().stream().map(BusinessAreaResponse::of).toList(),
                company.getCompanySize(),
                company.getAnnualRevenue(),
                company.getRevenueUnit(),
                company.getEmployeeCount(),
                company.getAddress(),
                company.getFoundedOn(),
                company.getSummary(),
                company.getBenefits(),
                company.getMemo(),
                openPostings,
                company.getCreatedAt(),
                company.getUpdatedAt(),
                null
        );
    }

    /**
     * 상세 조회(GET·company_get)에서만 채운다 — 이 기업을 지우면 소속 공고(마감 지난 것 포함)의 직무 전부에서 직접 출처
     * 연결이 CASCADE 로 사라지는 내 자소서 수. 삭제 확인창이 보여 준다. 목록·수정 응답은 null.
     */
    public CompanyResponse withLinkedSelfIntroCount(long count) {
        return new CompanyResponse(id, name, websiteUrl, industries, businesses, companySize, annualRevenue, revenueUnit,
                employeeCount, address, foundedOn, summary, benefits, memo, openPostings, createdAt, updatedAt, count);
    }
}
