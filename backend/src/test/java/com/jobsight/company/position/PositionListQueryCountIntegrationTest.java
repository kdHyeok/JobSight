package com.jobsight.company.position;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.position.dto.PositionResponse;
import com.jobsight.company.position.dto.PositionSummary;
import com.jobsight.company.posting.ApplicationStatus;
import com.jobsight.company.posting.EmploymentType;
import com.jobsight.company.posting.JobPostingService;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.posting.dto.JobPostingResponse;
import com.jobsight.company.posting.dto.PositionNameRequest;
import com.jobsight.company.posting.dto.StepRequest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 직무 목록(GET /api/positions) 1회의 SQL 수 상한 — 설계 1.3 P1, 1.6.
 * EAGER 컬렉션(직무 스택·참고 정보, 공고 절차, 기업 업종·주요 사업)이 행마다 SQL 을 내지 않고
 * default_batch_fetch_size 로 묶이는지 본다. 통계는 이 테스트에서만 켠다.
 */
@Testcontainers
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class PositionListQueryCountIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final int POSTINGS = 5;
    /**
     * 배치 페치 없이 39회, 있으면 9회였다(docs/backend.md). 9회는 직무·공고·기업·참고 정보 조회 4회와
     * EAGER 컬렉션 5종을 한 번씩 묶어 읽은 5회다. 컬렉션당 100건 이하면 데이터 수와 무관하다.
     */
    private static final long MAX_STATEMENTS = 9;

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean CurrentUser currentUser;
    @Autowired PositionService positions;
    @Autowired JobPostingService postings;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void owner() {
        given(currentUser.id()).willReturn(OWNER_ID);
        jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now()) on conflict (id) do nothing",
                OWNER_ID, "position-sql-it@example.com", "position-sql-it-sub");
    }

    /** 기업 5 · 공고 5(절차 2) · 직무 10(스택 2, 참고 정보 1). 모든 EAGER 컬렉션에 행이 있다. */
    private void seed() {
        UUID referenceId = UUID.randomUUID();
        jdbc.update("insert into reference_items (id, owner_id, kind, title, created_at, updated_at) "
                + "values (?, ?, 'ARTICLE', '참고 기사', now(), now())", referenceId, OWNER_ID);
        for (int i = 0; i < POSTINGS; i++) {
            JobPostingResponse posting = postings.create(new JobPostingRequest(null, "회사 " + i, "공채 " + i, null,
                    EmploymentType.FULL_TIME, null, ApplicationStatus.INTERESTED, null, null,
                    List.of(new PositionNameRequest(null, "백엔드"), new PositionNameRequest(null, "프론트엔드")),
                    List.of(new StepRequest("서류", null, null, null), new StepRequest("면접", null, null, null))));
            jdbc.update("insert into company_industries (company_id, industry) values (?, 'IT')", posting.companyId());
            jdbc.update("insert into company_businesses (company_id, seq, name) values (?, 0, '플랫폼')",
                    posting.companyId());
            for (UUID positionId : posting.positions().stream().map(PositionSummary::id).toList()) {
                jdbc.update("insert into position_tech_stack (position_id, tech) values (?, 'Java'), (?, 'Vue')",
                        positionId, positionId);
                jdbc.update("insert into position_references (position_id, reference_id) values (?, ?)",
                        positionId, referenceId);
            }
        }
    }

    @Test
    void positionListStaysUnderStatementLimit() {
        seed();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<PositionResponse> list = positions.findAll(null);

        long statements = statistics.getPrepareStatementCount();
        assertThat(list).hasSize(POSTINGS * 2);
        assertThat(list).allSatisfy(p -> {
            assertThat(p.techStack()).containsExactlyInAnyOrder("Java", "Vue");
            assertThat(p.references()).hasSize(1);
        });
        assertThat(statements).as("직무 목록 1회의 SQL 수").isLessThanOrEqualTo(MAX_STATEMENTS);
    }
}
