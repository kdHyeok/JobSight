package com.jobsight.company.company;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "companies")
public class Company {
    @Id
    private UUID id;

    /** 계정별 데이터 분리의 기준. 소유자는 생성 후 바뀌지 않는다. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    /** 업종 다중값. 입력한 순서를 유지한다. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "company_industries", joinColumns = @JoinColumn(name = "company_id"))
    @Column(name = "industry", length = 60, nullable = false)
    private Set<String> industries = new LinkedHashSet<>();

    /** 주요 사업 다중값. 업종과 달리 설명이 붙고 순서가 의미를 가진다. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "company_businesses", joinColumns = @JoinColumn(name = "company_id"))
    @OrderColumn(name = "seq")
    private List<BusinessArea> businesses = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "company_size", length = 20)
    private CompanySize companySize;

    /** 원 단위. 억/조 표기는 화면에서 만든다. */
    @Column(name = "annual_revenue")
    private Long annualRevenue;

    /** annualRevenue 는 원 단위 정본, 이 값은 사용자가 고른 입력·표시 단위다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "revenue_unit", length = 20)
    private RevenueUnit revenueUnit;

    @Column(name = "employee_count")
    private Integer employeeCount;

    @Column(length = 200)
    private String address;

    /** 설립연월. 연월만 입력받고 1일로 저장한다. */
    @Column(name = "founded_on")
    private LocalDate foundedOn;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String benefits;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Company() {
    }

    public Company(UUID ownerId, CompanyAttributes attributes) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        apply(attributes);
    }

    public void update(CompanyAttributes attributes) {
        apply(attributes);
        this.updatedAt = Instant.now();
    }

    private void apply(CompanyAttributes attributes) {
        this.name = attributes.name();
        this.websiteUrl = attributes.websiteUrl();
        // 새 컬렉션을 대입하지 않는다 — 저장마다 컬렉션 표 전체를 DELETE·INSERT 하게 된다(설계 1.3 P8).
        // 업종은 빠진 것만 빼고 새것만 더한다. 주요 사업은 순서(seq)가 의미라 자리별로 바뀐 칸만 고치고 끝에서 늘이거나 줄인다.
        Set<String> industries = new LinkedHashSet<>(attributes.industries());
        this.industries.retainAll(industries);
        this.industries.addAll(industries);
        List<BusinessArea> businesses = attributes.businesses();
        while (this.businesses.size() > businesses.size()) this.businesses.remove(this.businesses.size() - 1);
        for (int i = 0; i < businesses.size(); i++) {
            if (i >= this.businesses.size()) this.businesses.add(businesses.get(i));
            else if (!this.businesses.get(i).equals(businesses.get(i))) this.businesses.set(i, businesses.get(i));
        }
        this.companySize = attributes.companySize();
        this.annualRevenue = attributes.annualRevenue();
        this.revenueUnit = attributes.annualRevenue() == null ? null : attributes.revenueUnit();
        this.employeeCount = attributes.employeeCount();
        this.address = attributes.address();
        this.foundedOn = attributes.foundedOn();
        this.summary = attributes.summary();
        this.benefits = attributes.benefits();
        this.memo = attributes.memo();
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * 직접 입력한 회사명으로 기존 기업을 찾는 키. 공백 제거 + 소문자.
     * V10 의 unique index companies_owner_name_key 와 같은 규칙이어야 한다.
     */
    public static String nameKey(String name) {
        return name.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getWebsiteUrl() { return websiteUrl; }
    public Set<String> getIndustries() { return industries; }
    public List<BusinessArea> getBusinesses() { return businesses; }
    public CompanySize getCompanySize() { return companySize; }
    public Long getAnnualRevenue() { return annualRevenue; }
    public RevenueUnit getRevenueUnit() { return revenueUnit; }
    public Integer getEmployeeCount() { return employeeCount; }
    public String getAddress() { return address; }
    public LocalDate getFoundedOn() { return foundedOn; }
    public String getSummary() { return summary; }
    public String getBenefits() { return benefits; }
    public String getMemo() { return memo; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
