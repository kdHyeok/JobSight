package com.jobsight.company.common;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.company.Company;
import com.jobsight.company.company.CompanyService;
import com.jobsight.company.company.dto.CompanyRequest;
import com.jobsight.company.company.dto.CompanyResponse;
import com.jobsight.company.position.Position;
import com.jobsight.company.position.PositionService;
import com.jobsight.company.position.dto.PositionOption;
import com.jobsight.company.position.dto.PositionRequest;
import com.jobsight.company.position.dto.PositionResponse;
import com.jobsight.company.position.dto.PositionSummary;
import com.jobsight.company.posting.ApplicationStatus;
import com.jobsight.company.posting.EmploymentType;
import com.jobsight.company.posting.JobPostingService;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.posting.dto.JobPostingResponse;
import com.jobsight.company.posting.dto.PositionNameRequest;
import com.jobsight.company.resume.Resume;
import com.jobsight.company.resume.ResumeService;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.resume.dto.ResumeResponse;
import com.jobsight.company.resume.dto.ResumeSummary;
import com.jobsight.company.selfintro.SelfIntroduction;
import com.jobsight.company.selfintro.SelfIntroductionService;
import com.jobsight.company.selfintro.dto.SelfIntroductionCreateRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse;
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
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 성능 트랙(설계 1.3 P3·P4·P7·P8)의 회귀 방지. 응답 내용과 함께 "읽은 엔티티 수"와 "컬렉션 통째 재생성 수"를
 * Hibernate 통계로 단언한다. 전·후 실측은 docs/backend.md 「조회 정리」 표. 통계는 이 테스트에서만 켠다.
 */
@Testcontainers
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class QueryCleanupIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c4");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean CurrentUser currentUser;
    @Autowired ResumeService resumes;
    @Autowired SelfIntroductionService selfIntros;
    @Autowired PositionService positions;
    @Autowired JobPostingService postings;
    @Autowired CompanyService companies;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void owner() {
        given(currentUser.id()).willReturn(OWNER_ID);
        jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now()) on conflict (id) do nothing",
                OWNER_ID, "query-cleanup-it@example.com", "query-cleanup-it-sub");
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private JobPostingResponse posting(String company, String... positionNames) {
        List<PositionNameRequest> names = new ArrayList<>();
        for (String name : positionNames) names.add(new PositionNameRequest(null, name));
        return postings.create(new JobPostingRequest(null, company, company + " 공채", null, EmploymentType.FULL_TIME,
                null, ApplicationStatus.INTERESTED, null, null, names, List.of()));
    }

    private long loads(Class<?> entity) {
        return statistics.getEntityStatistics(entity.getName()).getLoadCount();
    }

    private long recreates(Class<?> entity, String collection) {
        return statistics.getCollectionStatistics(entity.getName() + "." + collection).getRecreateCount();
    }

    private void measure(String label) {
        System.out.println("MEASURE " + label + " statements=" + statistics.getPrepareStatementCount()
                + " entityLoads=" + statistics.getEntityLoadCount());
    }

    /** P3: 이력서에 연결된 자소서만 읽는다. 소유자 자소서 전체를 읽고 자바에서 거르지 않는다. */
    @Test
    void selfIntroListByResumeReadsOnlyLinkedRows() {
        UUID target = resumes.create(new ResumeRequest("대상 이력서", null, List.of(), null)).id();
        UUID other = resumes.create(new ResumeRequest("다른 이력서", null, List.of(), null)).id();
        for (int i = 0; i < 6; i++) {
            UUID resumeId = i < 2 ? target : other;
            selfIntros.create(new SelfIntroductionCreateRequest(List.of(resumeId), null, null, "문항 " + i, "답변 " + i));
        }
        statistics.clear();

        List<SelfIntroductionResponse> found = selfIntros.findAll(null, target);

        measure("P3 selfIntro.findAll(resumeId)");
        assertThat(found).extracting(SelfIntroductionResponse::question).containsExactlyInAnyOrder("문항 0", "문항 1");
        assertThat(found).allSatisfy(s -> assertThat(s.resumeIds()).containsExactly(target));
        assertThat(loads(SelfIntroduction.class)).as("읽은 자소서 엔티티 수").isEqualTo(2);
    }

    /** P4: 이력서 목록은 content JSONB 를 읽지 않는다 — Resume 엔티티를 로드하지 않고 SQL 한 번. */
    @Test
    void resumeListDoesNotLoadContent() {
        UUID positionId = posting("목록사", "백엔드").positions().get(0).id();
        UUID linked = resumes.create(new ResumeRequest("직무 있음", null, List.of(positionId), null)).id();
        UUID bare = resumes.create(new ResumeRequest("직무 없음", null, List.of(), null)).id();
        statistics.clear();

        List<ResumeSummary> list = resumes.findAll();

        measure("P4 resume.findAll");
        assertThat(list).extracting(ResumeSummary::id).contains(linked, bare);
        assertThat(list).filteredOn(r -> r.id().equals(linked)).singleElement()
                .satisfies(r -> assertThat(r.positionIds()).containsExactly(positionId));
        assertThat(list).filteredOn(r -> r.id().equals(bare)).singleElement()
                .satisfies(r -> assertThat(r.positionIds()).isEmpty());
        assertThat(loads(Resume.class)).as("읽은 이력서 엔티티 수").isZero();
        assertThat(statistics.getPrepareStatementCount()).as("이력서 목록 SQL 수").isEqualTo(1);
    }

    /** P6: 편집기 선택지 요약은 엔티티 없이 SQL 한 번이다(전체 목록 findAll 은 PositionListQueryCountIntegrationTest 의 9회). */
    @Test
    void positionOptionsAreOneStatement() {
        JobPostingResponse created = posting("요약사", "백엔드", "프론트엔드");
        statistics.clear();

        List<PositionOption> options = positions.findOptions();

        measure("P6 position.findOptions");
        assertThat(options).filteredOn(o -> o.postingId().equals(created.id()))
                .extracting(PositionOption::name, PositionOption::postingTitle, PositionOption::companyId,
                        PositionOption::companyName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("백엔드", "요약사 공채", created.companyId(), "요약사"),
                        org.assertj.core.groups.Tuple.tuple("프론트엔드", "요약사 공채", created.companyId(), "요약사"));
        assertThat(options).allSatisfy(o -> assertThat(o.updatedAt()).isNotNull());
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).as("직무 요약 SQL 수").isEqualTo(1);
    }

    /** P7: 소유 확인은 id 만 읽는다 — 직무·이력서 엔티티(와 EAGER 컬렉션)를 로드하지 않는다. 남의 id 는 그대로 404. */
    @Test
    void ownershipChecksDoNotLoadEntities() {
        List<UUID> positionIds = posting("소유사", "백엔드", "프론트엔드", "데이터").positions().stream()
                .map(PositionSummary::id).toList();
        ResumeResponse resume = resumes.create(new ResumeRequest("소유 확인", null, List.of(), null));
        statistics.clear();

        ResumeResponse saved = resumes.update(resume.id(),
                new ResumeRequest(resume.name(), null, positionIds, resume.version()));
        measure("P7 resume.update(positionIds 3)");
        assertThat(saved.positionIds()).containsExactlyInAnyOrderElementsOf(positionIds);
        assertThat(loads(Position.class)).as("이력서 저장이 읽은 직무 엔티티 수").isZero();

        statistics.clear();
        SelfIntroductionResponse created = selfIntros.create(new SelfIntroductionCreateRequest(
                List.of(resume.id()), positionIds, null, "소유 확인 문항", "답변"));
        measure("P7 selfIntro.create(resume 1, positions 3)");
        assertThat(created.resumeIds()).containsExactly(resume.id());
        assertThat(loads(Position.class)).as("자소서 생성이 읽은 직무 엔티티 수").isZero();
        assertThat(loads(Resume.class)).as("자소서 생성이 읽은 이력서 엔티티 수").isZero();

        UUID foreign = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resumes.update(resume.id(),
                        new ResumeRequest(resume.name(), null, List.of(foreign), saved.version())))
                .isInstanceOf(ResourceNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selfIntros.create(new SelfIntroductionCreateRequest(
                        List.of(foreign), null, null, "남의 이력서", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** 1.5·P10: V29 인덱스. 토큰 두 개는 hash(긴 토큰이 btree 행 크기 상한을 넘을 수 있다). */
    @Test
    void performanceIndexesExist() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select indexname, indexdef from pg_indexes
                 where indexname in ('oauth2_authorization_access_token_idx', 'oauth2_authorization_refresh_token_idx',
                                     'job_postings_target_position_idx', 'reference_items_related_position_idx',
                                     'position_references_reference_idx')
                """);
        assertThat(rows).hasSize(5);
        assertThat(rows).filteredOn(r -> r.get("indexname").toString().startsWith("oauth2_"))
                .allSatisfy(r -> assertThat(r.get("indexdef").toString()).contains("USING hash"));
    }

    /** P8: 바뀌지 않은 컬렉션은 저장할 때 통째로 지우고 다시 넣지 않는다. 바뀐 값은 그대로 반영된다. */
    @Test
    void unchangedCollectionsAreNotRecreated() {
        JobPostingResponse created = posting("컬렉션사", "백엔드");
        UUID companyId = created.companyId();
        UUID positionId = created.positions().get(0).id();
        Map<String, Object> company = Map.of("name", "컬렉션사", "industries", List.of("IT", "금융"),
                "businesses", List.of(Map.of("name", "플랫폼", "description", "B2B"), Map.of("name", "결제")));
        companies.update(companyId, mapper.convertValue(company, CompanyRequest.class));
        Map<String, Object> position = Map.of("name", "백엔드", "techStack", List.of("Java", "Spring"));
        positions.update(positionId, mapper.convertValue(position, PositionRequest.class));
        statistics.clear();

        companies.update(companyId, mapper.convertValue(company, CompanyRequest.class));
        positions.update(positionId, mapper.convertValue(position, PositionRequest.class));

        measure("P8 company.update + position.update (같은 값)");
        assertThat(recreates(Company.class, "industries")).isZero();
        assertThat(recreates(Company.class, "businesses")).isZero();
        assertThat(recreates(Position.class, "techStack")).isZero();

        Map<String, Object> changedCompany = Map.of("name", "컬렉션사", "industries", List.of("금융", "커머스"),
                "businesses", List.of(Map.of("name", "결제"), Map.of("name", "플랫폼", "description", "B2C")));
        CompanyResponse company2 = companies.update(companyId, mapper.convertValue(changedCompany, CompanyRequest.class));
        PositionResponse position2 = positions.update(positionId,
                mapper.convertValue(Map.of("name", "백엔드", "techStack", List.of("Kotlin", "Spring")), PositionRequest.class));

        CompanyResponse reread = companies.findById(companyId);
        assertThat(reread.industries()).containsExactlyInAnyOrder("금융", "커머스");
        assertThat(reread.businesses()).extracting("name", "description")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("결제", null),
                        org.assertj.core.groups.Tuple.tuple("플랫폼", "B2C"));
        assertThat(company2.industries()).containsExactlyInAnyOrder("금융", "커머스");
        assertThat(positions.findById(positionId).techStack()).containsExactlyInAnyOrder("Kotlin", "Spring");
        assertThat(position2.techStack()).containsExactlyInAnyOrder("Kotlin", "Spring");
    }
}
