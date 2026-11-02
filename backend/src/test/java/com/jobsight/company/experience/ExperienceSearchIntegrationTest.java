package com.jobsight.company.experience;

import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.experience.dto.ExperienceSearchRequest.Field;
import com.jobsight.company.experience.dto.ExperienceSearchResult;
import com.jobsight.company.experience.dto.ExperienceViews;
import com.jobsight.company.mcp.McpOAuthConfig;
import com.jobsight.company.mcp.McpPrincipal;
import com.jobsight.company.mcp.McpTools;
import com.jobsight.company.posting.ApplicationStatus;
import com.jobsight.company.posting.EmploymentType;
import com.jobsight.company.posting.JobPostingService;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.posting.dto.JobPostingResponse;
import com.jobsight.company.posting.dto.PositionNameRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 3단계: 공고를 저장하지 않는 경험 찾기와 묶음 기반 목차·missing — 설계 7절 3단계 검증 칸.
 * MCP 도구 호출(McpTools.call)로 스키마·검증·소유자 격리를 실제 경로로 본다. 테스트마다 새 계정을 만든다.
 */
@Testcontainers
@SpringBootTest
class ExperienceSearchIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired McpTools tools;
    @Autowired JobPostingService postings;
    @Autowired JdbcTemplate jdbc;

    private McpPrincipal principal;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 새 계정을 만들고 그 계정의 MCP 토큰으로 요청한다. */
    private UUID signIn() {
        UUID owner = UUID.randomUUID();
        jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now())", owner, owner + "@example.com", "sub-" + owner);
        principal = new McpPrincipal(owner, Set.copyOf(McpOAuthConfig.SCOPES));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        return owner;
    }

    private Object call(String tool, Map<String, Object> arguments) {
        return tools.call(tool, arguments, principal);
    }

    private static Map<String, Object> project(String name, String startYm, String techStack, String description) {
        return Map.of("name", name, "startYm", startYm, "endYm", "2025.06", "techStack", techStack, "description", description);
    }

    /** 기준 이력서 A: 프로젝트 둘과 경력 하나. */
    @SuppressWarnings("unchecked")
    private UUID resumeA() {
        Map<String, Object> created = (Map<String, Object>) call("resume_create", Map.of("request", Map.of("name", "백엔드 A",
                "content", Map.of(
                        "projects", List.of(
                                project("결제 정산 배치", "2025.03", "Java, Kafka, Spring Boot", "정산 배치 지연을 Kafka 파티션 조정으로 40분에서 6분으로 줄였다"),
                                project("사내 메시지 큐 이전", "2024.01", "AWS EC2", "레거시 큐를 Kafka로 옮겼다")),
                        "experiences", List.of(Map.of("company", "A사", "startYm", "2023.01", "endYm", "2023.12",
                                "description", "백엔드 인턴"))))));
        return UUID.fromString(created.get("id").toString());
    }

    private static Map<String, Object> requirement(String text, String field, String kind, String quote) {
        return Map.of("text", text, "field", field, "kind", kind, "quote", quote);
    }

    /** 고르기 전의 공고는 저장하지 않는다. 필수 기술 결핍은 uncovered, 기술 환경 나열은 아니다. 지원 자격은 점수와 분리. */
    @Test
    void unsavedPostingIsNotStoredAndReportsOnlyRequiredGaps() {
        signIn();
        resumeA();
        long postingsBefore = jdbc.queryForObject("select count(*) from job_postings", Long.class);
        long positionsBefore = jdbc.queryForObject("select count(*) from positions", Long.class);

        ExperienceSearchResult result = (ExperienceSearchResult) call("experience_search", Map.of("request", Map.of(
                "posting", Map.of("companyName", "B사", "positionName", "서버 개발",
                        "requiredSkills", "Kafka 사용 경험, Kubernetes 운영 경험", "techStack", List.of("Tableau", "Kafka")),
                "requirements", List.of(
                        requirement("Kafka", "requiredSkills", "SKILL", "Kafka 사용 경험"),
                        requirement("Kubernetes", "requiredSkills", "SKILL", "Kubernetes 운영 경험"),
                        requirement("Tableau", "techStack", "SKILL", "Tableau"),
                        requirement("AWS", "preferredSkills", "SKILL", "AWS 경험 우대"),
                        requirement("경력 3년 이상", "qualifications", "QUALIFICATION", "경력 3년 이상")))));

        assertThat(jdbc.queryForObject("select count(*) from job_postings", Long.class)).isEqualTo(postingsBefore);
        assertThat(jdbc.queryForObject("select count(*) from positions", Long.class)).isEqualTo(positionsBefore);

        assertThat(result.query().saved()).isFalse();
        assertThat(result.query().requirementsSource()).isEqualTo("CLIENT");
        assertThat(result.warnings()).isEmpty();
        // 기술 환경 칸에만 있는 tableau 는 결핍이 아니다. 내 자료에 없는 필수 kubernetes 는 결핍이다.
        assertThat(result.uncovered()).extracting(ExperienceSearchResult.Uncovered::key).containsExactly("kubernetes");
        assertThat(result.uncovered().get(0).reason()).isEqualTo("NO_MATCH");
        // 지원 자격은 요구 키(점수)에 들어가지 않고 qualifications 로만 나온다.
        assertThat(result.qualifications()).extracting(ExperienceSearchResult.Qualification::text).containsExactly("경력 3년 이상");
        assertThat(result.query().requirements()).extracting(ExperienceSearchResult.RequirementKey::key)
                .containsExactly("kafka", "kubernetes", "tableau", "aws");
        assertThat(result.coverageIncomplete()).isTrue();
        assertThat(result.context().searchConfig()).isNotBlank();
        assertThat(result.context().dataVersion()).isNotBlank();

        ExperienceSearchResult.Result first = result.results().get(0);
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.type()).isEqualTo("RESUME_ROW");
        assertThat(first.title()).isEqualTo("결제 정산 배치");
        assertThat(first.source().section()).isEqualTo("projects");
        assertThat(first.source().index()).isZero();
        assertThat(first.source().resumeName()).isEqualTo("백엔드 A");
        assertThat(first.source().textHash()).hasSize(16);
        assertThat(first.source().excerpt()).contains("결제 정산 배치");
        assertThat(first.why()).anySatisfy(why -> {
            assertThat(why.signal()).isEqualTo("REQUIREMENT");
            assertThat(why.matched()).containsExactly("kafka");
            assertThat(why.field().name()).isEqualTo("requiredSkills");
            assertThat(why.quote()).isEqualTo("Kafka 사용 경험");
        });
        // AWS EC2 ⊇ AWS 는 토큰 포함 일치로 우대 칸에서 맞는다.
        assertThat(result.results()).anySatisfy(row -> {
            assertThat(row.title()).isEqualTo("사내 메시지 큐 이전");
            assertThat(row.why()).anySatisfy(why -> assertThat(why.matched()).containsExactly("aws"));
        });
    }

    /**
     * 소형 골드셋: 행 6개, 요구만 넘기고 공고 본문은 비워 LEXICAL 을 끈다 — 순서가 REQUIREMENT 점수 그대로다.
     * N=6, df(kafka)=2(태그 alpha·본문 charlie), df(redis)=1, df(go)=2 이므로
     * 질의 1: bravo 1.0×1×ln7=1.95 > alpha 1.0×1×ln4=1.39 > tie-a = tie-b 0.7×ln4=0.97 > charlie 1.0×0.5×ln4=0.69.
     * 질의 2: 같은 kafka 를 기술 환경 칸(0.3)에 두면 요구 역량 칸의 go(1.39)가 앞선다. noise 는 어디에도 없다.
     * 칸 가중·정확/포함 일치·희소도·RRF 방향·동점 키 정렬 중 하나라도 바뀌면 순서가 달라진다.
     */
    @Test
    @SuppressWarnings("unchecked")
    void goldSetOrdersByFieldWeightMatchRarityAndKey() {
        signIn();
        Map<String, Object> created = (Map<String, Object>) call("resume_create", Map.of("request", Map.of("name", "골드셋",
                "content", Map.of("projects", List.of(
                        project("alpha", "2024.01", "Kafka", "메시지 처리를 맡았다"),
                        project("bravo", "2024.02", "Redis", "캐시 처리를 맡았다"),
                        project("charlie", "2024.03", "Java", "Kafka 컨슈머를 튜닝했다"),
                        project("tie-b", "2024.04", "Go", "서버를 만들었다"),
                        project("tie-a", "2024.05", "Go", "서버를 만들었다"),
                        project("noise", "2024.06", "Excel", "문서를 정리했다"))))));
        UUID gold = UUID.fromString(created.get("id").toString());

        ExperienceSearchResult first = (ExperienceSearchResult) call("experience_search", Map.of("request", Map.of(
                "posting", Map.of(),
                "requirements", List.of(
                        requirement("Kafka", "requiredSkills", "SKILL", "Kafka 경험"),
                        requirement("Redis", "requiredSkills", "SKILL", "Redis 경험"),
                        requirement("Go", "responsibilities", "SKILL", "Go 서버 개발")))));
        assertThat(first.results()).extracting(ExperienceSearchResult.Result::title)
                .containsExactly("bravo", "alpha", "tie-a", "tie-b", "charlie");
        assertThat(first.results()).extracting(ExperienceSearchResult.Result::rank).containsExactly(1, 2, 3, 4, 5);
        // 출처가 정답 행을 가리킨다: 같은 이력서의 원래 행 번호.
        assertThat(first.results()).extracting(row -> row.source().index()).containsExactly(1, 0, 4, 3, 2);
        assertThat(first.results()).allSatisfy(row -> {
            assertThat(row.source().resumeId()).isEqualTo(gold);
            assertThat(row.source().section()).isEqualTo("projects");
            assertThat(row.why()).extracting(ExperienceSearchResult.Why::signal).containsOnly("REQUIREMENT");
        });

        ExperienceSearchResult second = (ExperienceSearchResult) call("experience_search", Map.of("request", Map.of(
                "posting", Map.of(),
                "requirements", List.of(
                        requirement("Kafka", "techStack", "SKILL", "Kafka"),
                        requirement("Go", "requiredSkills", "SKILL", "Go 경험")))));
        assertThat(second.results()).extracting(ExperienceSearchResult.Result::title)
                .containsExactly("tie-a", "tie-b", "alpha", "charlie");
    }

    /**
     * uncovered 경계: 지원자격·요구 역량 칸만 결핍으로 본다. 본문에는 보이지만 키가 맞지 않으면 TAG_MISMATCH_POSSIBLE.
     * "Spring-Batch" 는 하이픈으로 나뉘어 언급 키 springbatch 가 되지 않지만 평문 키에는 보인다.
     */
    @Test
    void uncoveredOnlyFromGapFieldsAndSeparatesTagMismatch() {
        signIn();
        call("resume_create", Map.of("request", Map.of("name", "정산", "content", Map.of("projects", List.of(
                project("정산 이관", "2024.01", "Java", "Spring-Batch 로 정산 작업을 옮겼다"))))));

        ExperienceSearchResult result = (ExperienceSearchResult) call("experience_search", Map.of("request", Map.of(
                "posting", Map.of(),
                "requirements", List.of(
                        requirement("Spring Batch", "requiredSkills", "SKILL", "Spring Batch 경험"),
                        requirement("Databricks", "preferredSkills", "SKILL", "Databricks 우대"),
                        requirement("Terraform", "responsibilities", "SKILL", "Terraform 으로 인프라 관리"),
                        requirement("Kubernetes", "qualifications", "SKILL", "Kubernetes 운영 가능자")))));

        // 우대(databricks)·담당업무(terraform) 칸의 키는 내 자료에 없어도 결핍이 아니다.
        assertThat(result.uncovered()).extracting(ExperienceSearchResult.Uncovered::key,
                        ExperienceSearchResult.Uncovered::field, ExperienceSearchResult.Uncovered::reason)
                .containsExactly(
                        tuple("springbatch", Field.requiredSkills, "TAG_MISMATCH_POSSIBLE"),
                        tuple("kubernetes", Field.qualifications, "NO_MATCH"));
    }

    /**
     * 요구 없이 지원 자격 본문만 넘기면: 서버 사전은 기술 키만 뽑아 지원자격 칸의 요구로 쓴다(결핍 보고 대상).
     * 경력 연수 같은 자격 문장은 서버가 분류하지 않으므로 qualifications[] 는 비고 REQUIREMENTS_PARTIAL 로 알린다.
     */
    @Test
    void serverFallbackFromQualificationsTextKeepsSkillKeysOnly() {
        signIn();
        resumeA();
        ExperienceSearchResult result = (ExperienceSearchResult) call("experience_search", Map.of("request", Map.of(
                "posting", Map.of("qualifications", "Kubernetes 운영 경력 3년 이상, Kafka 사용 경험"))));

        assertThat(result.query().requirementsSource()).isEqualTo("SERVER_DICTIONARY");
        assertThat(result.warnings()).containsExactly("REQUIREMENTS_PARTIAL");
        assertThat(result.query().requirements()).extracting(ExperienceSearchResult.RequirementKey::key,
                        ExperienceSearchResult.RequirementKey::field)
                .containsExactly(tuple("kubernetes", Field.qualifications),
                        tuple("kafka", Field.qualifications));
        assertThat(result.uncovered()).extracting(ExperienceSearchResult.Uncovered::key,
                        ExperienceSearchResult.Uncovered::field, ExperienceSearchResult.Uncovered::reason)
                .containsExactly(tuple("kubernetes", Field.qualifications, "NO_MATCH"));
        assertThat(result.qualifications()).isEmpty();
    }

    /** 요구를 넘기지 않으면 서버 사전으로 뽑고 부분 추출임을 알린다. */
    @Test
    void serverDictionaryFallbackWarnsPartial() {
        signIn();
        resumeA();
        ExperienceSearchResult result = (ExperienceSearchResult) call("experience_search", Map.of("request", Map.of(
                "posting", Map.of("requiredSkills", "Kubernetes 운영 경험과 Kafka", "techStack", List.of("Tableau")))));

        assertThat(result.query().requirementsSource()).isEqualTo("SERVER_DICTIONARY");
        assertThat(result.warnings()).containsExactly("REQUIREMENTS_PARTIAL");
        assertThat(result.uncovered()).extracting(ExperienceSearchResult.Uncovered::key).containsExactly("kubernetes");
        assertThat(result.results()).isNotEmpty();
    }

    /** 등록 직무는 저장된 다섯 칸을 쓴다. 남의 직무는 404, 남의 이력서 행은 후보가 아니다. */
    @Test
    void positionIdUsesStoredFieldsAndIsOwnerScoped() {
        signIn();
        McpPrincipal other = principal;
        call("resume_create", Map.of("request", Map.of("name", "남의 이력서", "content", Map.of("projects",
                List.of(project("남의 카프카 프로젝트", "2024.01", "Kafka", "Kafka"))))));
        JobPostingResponse otherPosting = postings.create(new JobPostingRequest(null, "C사", "C사 공채", null,
                EmploymentType.FULL_TIME, null, ApplicationStatus.INTERESTED, null, null,
                List.of(new PositionNameRequest(null, "백엔드")), List.of()));
        UUID otherPosition = otherPosting.positions().get(0).id();

        signIn();
        resumeA();
        JobPostingResponse posting = postings.create(new JobPostingRequest(null, "B사", "B사 공채", null,
                EmploymentType.FULL_TIME, null, ApplicationStatus.INTERESTED, null, null,
                List.of(new PositionNameRequest(null, "서버 개발")), List.of()));
        UUID positionId = posting.positions().get(0).id();
        jdbc.update("update positions set required_skills = ? where id = ?", "Kafka 기반 메시징 경험", positionId);
        jdbc.update("insert into position_tech_stack (position_id, tech) values (?, ?)", positionId, "Redis");

        ExperienceSearchResult result = (ExperienceSearchResult) call("experience_search",
                Map.of("request", Map.of("positionId", positionId.toString())));
        assertThat(result.query().saved()).isTrue();
        assertThat(result.query().position()).isEqualTo("B사 · 서버 개발");
        assertThat(result.results()).extracting(ExperienceSearchResult.Result::title)
                .contains("결제 정산 배치").doesNotContain("남의 카프카 프로젝트");
        assertThat(result.uncovered()).isEmpty();

        assertThatThrownBy(() -> call("experience_search", Map.of("request", Map.of("positionId", otherPosition.toString()))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> call("experience_search", Map.of("request", Map.of())))
                .hasMessageContaining("positionId");
        assertThat(other.userId()).isNotEqualTo(principal.userId());
    }

    /** 다른 이력서에만 있는 묶음이 resume_create·resume_get 응답 머리의 missing 에 나온다(설계 5.3). */
    @Test
    @SuppressWarnings("unchecked")
    void missingShowsGroupsOnlyInOtherResumes() {
        signIn();
        UUID a = resumeA();
        Map<String, Object> created = (Map<String, Object>) call("resume_create", Map.of("request", Map.of("name", "백엔드 B",
                "content", Map.of("projects", List.of(
                        project("결제 정산 배치", "2025.03", "Java", "같은 경험"),
                        project("사내 메시지 큐 이전", "2024.02", "Kafka", "시작 연월이 다르다"))))));
        assertThat(created.keySet().iterator().next()).isEqualTo("missing");
        List<ExperienceViews.Missing> missing = (List<ExperienceViews.Missing>) created.get("missing");
        assertThat(missing).extracting(ExperienceViews.Missing::title).containsExactly("사내 메시지 큐 이전", "A사");
        assertThat(missing).allSatisfy(item -> {
            assertThat(item.experienceId()).isNull();
            assertThat(item.sources()).extracting(ExperienceViews.Source::resumeId).containsOnly(a);
        });
        assertThat(missing.get(0).maybeUnlinked()).isTrue();
        assertThat(missing.get(1).maybeUnlinked()).isFalse();
        assertThat(missing.get(1).kind()).isEqualTo(ExperienceKind.CAREER);

        UUID b = UUID.fromString(created.get("id").toString());
        Map<String, Object> full = (Map<String, Object>) call("resume_get", Map.of("id", b.toString()));
        assertThat(full.keySet().iterator().next()).isEqualTo("missing");
        assertThat(full).containsKeys("id", "name", "content", "version");

        ExperienceViews.Outline outline = (ExperienceViews.Outline) call("resume_get",
                Map.of("id", b.toString(), "view", "outline"));
        assertThat(outline.version()).isEqualTo(((Number) created.get("version")).longValue());
        assertThat(outline.sections()).hasSize(8);
        ExperienceViews.Section projects = outline.sections().stream()
                .filter(section -> section.section().equals("projects")).findFirst().orElseThrow();
        assertThat(projects.count()).isEqualTo(2);
        assertThat(projects.rows()).extracting(ExperienceViews.RowHead::title).containsExactly("결제 정산 배치", "사내 메시지 큐 이전");
        assertThat(projects.rows().get(0).period()).isEqualTo("2025.03~2025.06");
        assertThat(projects.rows().get(0).experienceId()).isNull();
        assertThat(outline.missing()).extracting(ExperienceViews.Missing::title).containsExactly("사내 메시지 큐 이전", "A사");
        assertThat(outline.unlinkedRows()).hasSize(2);

        // A 는 B 에만 있는 묶음(2024.02 메시지 큐)이 빠져 있다.
        ExperienceViews.Outline outlineA = (ExperienceViews.Outline) call("resume_get",
                Map.of("id", a.toString(), "view", "outline"));
        assertThat(outlineA.missing()).extracting(ExperienceViews.Missing::title).containsExactly("사내 메시지 큐 이전");
    }

    /** experience_index 는 4단계와 같은 모양이다. experiences 는 늘 [] 이고 묶음은 이력서를 넘어 합쳐진다. */
    @Test
    void indexHasStageFourShapeAndGroupsAcrossResumes() {
        signIn();
        ExperienceViews.Index empty = (ExperienceViews.Index) call("experience_index", Map.of());
        assertThat(empty.experiences()).isEmpty();
        assertThat(empty.unlinkedRows()).isEmpty();
        assertThat(empty.complete()).isTrue();

        UUID a = resumeA();
        call("resume_copy", Map.of("id", a.toString(), "request", Map.of("name", "백엔드 A 복제")));
        call("self_intro_create", Map.of("request", Map.of("resumeIds", List.of(a.toString()), "question", "지원 동기")));

        ExperienceViews.Index index = (ExperienceViews.Index) call("experience_index", Map.of());
        assertThat(index.experiences()).isNotNull().isEmpty();
        assertThat(index.unlinkedRows()).extracting(ExperienceViews.UnlinkedGroup::kind)
                .containsExactly(ExperienceKind.PROJECT, ExperienceKind.PROJECT, ExperienceKind.CAREER);
        assertThat(index.unlinkedRows()).allSatisfy(group -> assertThat(group.sources()).hasSize(2));
        assertThat(index.counts().unlinkedRows()).isEqualTo(6);
        assertThat(index.counts().groups()).isEqualTo(3);
        assertThat(index.counts().unknownSourceSelfIntros()).isEqualTo(1);

        ExperienceViews.Index careers = (ExperienceViews.Index) call("experience_index", Map.of("kinds", List.of("CAREER")));
        assertThat(careers.unlinkedRows()).extracting(ExperienceViews.UnlinkedGroup::title).containsExactly("A사");

        ExperienceViews.Index page = (ExperienceViews.Index) call("experience_index", Map.of("limit", 2));
        assertThat(page.unlinkedRows()).hasSize(2);
        assertThat(page.complete()).isFalse();
        ExperienceViews.Index next = (ExperienceViews.Index) call("experience_index",
                Map.of("limit", 2, "cursor", page.nextCursor()));
        assertThat(next.unlinkedRows()).extracting(ExperienceViews.UnlinkedGroup::title).containsExactly("A사");
        assertThat(next.complete()).isTrue();
    }

    /** 남의 이력서 outline 은 404 다. */
    @Test
    void outlineOfOthersResumeIsNotFound() {
        signIn();
        UUID foreign = resumeA();
        signIn();
        assertThatThrownBy(() -> call("resume_get", Map.of("id", foreign.toString(), "view", "outline")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> call("resume_get", Map.of("id", foreign.toString())))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
