package com.jobsight.company.selfintro;

import com.jobsight.company.auth.AppSessionUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.LinkSource;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.company.Company;
import com.jobsight.company.company.CompanyService;
import com.jobsight.company.company.dto.CompanyResponse;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.position.PositionService;
import com.jobsight.company.position.dto.PositionResponse;
import com.jobsight.company.mcp.McpController;
import com.jobsight.company.mcp.McpOAuthConfig;
import com.jobsight.company.mcp.McpPrincipal;
import com.jobsight.company.mcp.McpTools;
import com.jobsight.company.position.Position;
import com.jobsight.company.posting.ApplicationStatus;
import com.jobsight.company.posting.EmploymentType;
import com.jobsight.company.posting.JobPosting;
import com.jobsight.company.posting.JobPostingService;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.posting.dto.JobPostingResponse;
import com.jobsight.company.posting.dto.PositionNameRequest;
import com.jobsight.company.resume.Resume;
import com.jobsight.company.resume.ResumeService;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionConfirmRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionCreateRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionList;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse.PositionLinkView;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse.QuestionTypeView;
import com.jobsight.company.selfintro.dto.SelfIntroductionUpdateRequest;
import com.jobsight.company.user.UserRole;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 자소서 직접 출처(written_for)와 문항 유형을 실제 복합 FK 로 본다 — 설계 2.4·2.5, 7절 2단계.
 * CurrentUser 를 목으로 바꾸지 않는다. 출처(USER·AI)는 SecurityContext 의 실제 주체가 정한다:
 * 웹 세션은 AppSessionUser, MCP 토큰은 McpPrincipal(설계 3절 규칙 2).
 * 테스트마다 새 계정을 만들어 목록이 서로 섞이지 않게 한다. SQL 수 상한을 재려고 통계를 이 클래스에서만 켠다.
 */
@Testcontainers
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class SelfIntroductionRelationsIntegrationTest {
    /**
     * MCP 목록·상세 1회의 SQL 수 상한(2026-11-02 실측 6·6·7·7, docs/backend.md). 문항 1 + 문항 컬렉션 3(이력서·직무·유형)
     * + 이력서 이름 1 + 직무 이름 1. 필터가 있으면 대상 직무 이름 1회와 공고·기업 소유 확인(exists) 1회가 붙고,
     * 대상 직무 이름을 이미 읽었으면 출처용 직무 이름 조회는 생략된다. 컬렉션은 100건씩 묶여 계정의 문항이 100건 이하면 문항 수와 무관하다.
     */
    private static final long MAX_GET_STATEMENTS = 6;
    private static final long MAX_LIST_STATEMENTS = 6;
    private static final long MAX_FILTERED_LIST_STATEMENTS = 7;

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired SelfIntroductionService service;
    @Autowired SelfIntroductionAgentService agent;
    @Autowired JobPostingService postings;
    @Autowired ResumeService resumes;
    @Autowired PositionService positionService;
    @Autowired PositionRepository positionRepository;
    @Autowired CompanyService companies;
    @Autowired McpTools tools;
    @Autowired McpController mcp;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    private UUID owner;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 새 계정을 만들고 그 계정의 웹 세션으로 요청한다. */
    private UUID signIn() {
        owner = UUID.randomUUID();
        jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now())", owner, owner + "@example.com", "sub-" + owner);
        asWeb();
        return owner;
    }

    private void asWeb() {
        authenticate(new AppSessionUser(owner, owner + "@example.com", UserRole.USER));
    }

    /** 같은 계정의 MCP 토큰으로 요청한다. */
    private McpPrincipal asMcp() {
        McpPrincipal principal = new McpPrincipal(owner, Set.copyOf(McpOAuthConfig.SCOPES));
        authenticate(principal);
        return principal;
    }

    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private JobPostingResponse posting(String company, String... positions) {
        return postings.create(new JobPostingRequest(null, company, company + " 공채", null, EmploymentType.FULL_TIME,
                null, ApplicationStatus.INTERESTED, null, null,
                Arrays.stream(positions).map(name -> new PositionNameRequest(null, name)).toList(), List.of()));
    }

    private static UUID position(JobPostingResponse posting) {
        return posting.positions().get(0).id();
    }

    private SelfIntroductionList.Item only(SelfIntroductionList list) {
        assertThat(list.results()).hasSize(1);
        return list.results().get(0);
    }

    private SelfIntroductionList listAll() {
        return agent.list(null, null, null, null, null, null, null, null);
    }

    /** MCP self_intro_update 인자. positionIds·questionTypes·expectedVersion 은 null 이면 빼서 보낸다(유지·생략). */
    private Map<String, Object> mcpUpdate(UUID id, String question, String answer, List<UUID> positionIds,
                                          List<String> questionTypes, Long expectedVersion) {
        Map<String, Object> request = new java.util.HashMap<>(Map.of("resumeIds", List.of(), "question", question, "answer", answer));
        if (positionIds != null) request.put("positionIds", positionIds.stream().map(UUID::toString).toList());
        if (questionTypes != null) request.put("questionTypes", questionTypes);
        if (expectedVersion != null) request.put("expectedVersion", expectedVersion);
        return Map.of("id", id.toString(), "request", request);
    }

    /** 지금 저장된 version. 웹 화면이 목록에서 읽는 값과 같다. */
    private long version(UUID id) {
        return service.findById(id).version();
    }

    private String positionSource(UUID id, UUID positionId) {
        return jdbc.queryForObject("select source from self_introduction_positions where self_introduction_id = ? and position_id = ?",
                String.class, id, positionId);
    }

    private String typeSource(UUID id, Competency code) {
        return jdbc.queryForObject("select source from self_introduction_competencies where self_introduction_id = ? and competency_code = ?",
                String.class, id, code.name());
    }

    /** 다른 계정의 직무 id 로 연결하면 404 다. 서비스를 거치지 않은 행도 복합 FK 가 거부한다(설계 2.4). */
    @Test
    void foreignPositionIsRejectedByServiceAndDatabase() {
        signIn();
        UUID foreign = position(posting("남의 회사", "남의 직무"));

        UUID owner = signIn();
        UUID mine = position(posting("내 회사", "백엔드"));
        UUID id = service.create(new SelfIntroductionCreateRequest(null, List.of(mine), null, "지원 동기", null)).id();

        assertThatThrownBy(() -> service.create(new SelfIntroductionCreateRequest(null, List.of(foreign), null, "지원 동기", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(id, new SelfIntroductionUpdateRequest(List.of(), List.of(foreign), null, "지원 동기", null, version(id))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.findById(id).positionLinks()).extracting(PositionLinkView::positionId)
                .containsExactly(mine);
        assertThatThrownBy(() -> jdbc.update("insert into self_introduction_positions "
                + "(owner_id, self_introduction_id, position_id, source) values (?, ?, ?, 'USER')", owner, id, foreign))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 남의 문항·직무·공고·기업 id 는 MCP 읽기와 웹 확정 경로 모두 404 다(불변 조건 1).
     * DB 는 소유자가 다른 간선을 직무 쪽뿐 아니라 문항 쪽에서도, 문항 유형 표에서도 거부한다.
     */
    @Test
    void otherAccountIdsAreNotFoundOnReadAndConfirmAndRejectedByDatabase() {
        UUID other = signIn();
        JobPostingResponse theirs = posting("남의 회사", "남의 직무");
        UUID theirPosition = position(theirs);
        UUID theirIntro = service.create(new SelfIntroductionCreateRequest(null, List.of(theirPosition),
                List.of(Competency.MOTIVATION), "지원 동기", "답변")).id();

        UUID me = signIn();
        UUID mine = position(posting("내 회사", "백엔드"));
        UUID myIntro = service.create(new SelfIntroductionCreateRequest(null, List.of(mine), null, "지원 동기", "답변")).id();

        assertThatThrownBy(() -> agent.get(theirIntro)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> agent.list(null, null, theirPosition, null, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> agent.list(null, null, null, theirs.id(), null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> agent.list(null, null, null, null, theirs.companyId(), null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.confirm(theirIntro,
                new SelfIntroductionConfirmRequest(List.of(theirPosition), List.of(Competency.MOTIVATION))))
                .isInstanceOf(ResourceNotFoundException.class);
        asMcp();
        assertThatThrownBy(() -> agent.get(theirIntro)).isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> jdbc.update("insert into self_introduction_positions "
                + "(owner_id, self_introduction_id, position_id, source) values (?, ?, ?, 'USER')", me, theirIntro, mine))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into self_introduction_competencies "
                + "(owner_id, self_introduction_id, competency_code, source) values (?, ?, 'CONFLICT', 'USER')", me, theirIntro))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into self_introduction_competencies "
                + "(owner_id, self_introduction_id, competency_code, source) values (?, ?, 'CONFLICT', 'USER')", other, myIntro))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * MCP 토큰으로 self_intro_create 를 부르면(McpTools → 컨트롤러 → 서비스) 연결이 AI 로 저장되고 "AI 기록"으로 보인다.
     * 웹 세션으로 만든 연결은 USER 다. AI 기록은 웹의 확정 뒤에 "확정"이 된다(설계 3절 규칙 2·3·5).
     */
    @Test
    void mcpTokenRecordsAiAndWebSessionRecordsUserUntilConfirm() {
        signIn();
        UUID backend = position(posting("에이회사", "백엔드"));
        UUID data = position(posting("비회사", "데이터"));

        McpPrincipal principal = asMcp();
        var created = (SelfIntroductionResponse) tools.call("self_intro_create", Map.of("request", Map.of(
                "positionIds", List.of(backend.toString()), "questionTypes", List.of("MOTIVATION"),
                "question", "에이회사 지원 동기", "answer", "답변")), principal);
        UUID id = created.id();
        assertThat(positionSource(id, backend)).isEqualTo("AI");
        assertThat(typeSource(id, Competency.MOTIVATION)).isEqualTo("AI");

        var item = only(agent.list(null, null, backend, null, null, null, null, null));
        assertThat(item.sourceStatus()).isEqualTo("직접 출처");
        assertThat(item.sources().direct()).singleElement().satisfies(direct -> {
            assertThat(direct.position()).isEqualTo("백엔드");
            assertThat(direct.company()).isEqualTo("에이회사");
            assertThat(direct.trust()).isEqualTo("AI 기록");
        });
        assertThat(item.questionTypes()).singleElement().satisfies(type -> {
            assertThat(type.code()).isEqualTo(Competency.MOTIVATION);
            assertThat(type.from()).isEqualTo("AI");
            assertThat(type.trust()).isEqualTo("AI 기록");
        });

        asWeb();
        UUID web = service.create(new SelfIntroductionCreateRequest(null, List.of(data), List.of(Competency.COLLABORATION),
                "협업 경험", "답변")).id();
        assertThat(positionSource(web, data)).isEqualTo("USER");
        assertThat(typeSource(web, Competency.COLLABORATION)).isEqualTo("USER");

        service.confirm(id, new SelfIntroductionConfirmRequest(List.of(backend), List.of(Competency.MOTIVATION)));

        var confirmed = only(agent.list(null, null, backend, null, null, null, null, null));
        assertThat(confirmed.sources().direct()).extracting(SelfIntroductionList.Direct::trust).containsExactly("확정");
        assertThat(confirmed.questionTypes()).extracting(SelfIntroductionList.QuestionType::trust).containsExactly("확정");
        assertThat(positionSource(id, backend)).isEqualTo("USER");
    }

    /**
     * MCP 수정은 직무·문항 유형 연결을 더하기만 한다. 사용자가 확정한 연결도, AI 기록도 지우지 못한다 —
     * AI 기록의 확정과 삭제는 웹 전용이다(설계 3절 규칙 3, 5.2). 웹 저장은 목록으로 맞춘다(✗ 해제).
     */
    @Test
    void mcpUpdateOnlyAddsLinksAndWebUpdateRemovesThem() {
        signIn();
        UUID a = position(posting("에이회사", "백엔드"));
        UUID b = position(posting("비회사", "프론트"));
        UUID id = service.create(new SelfIntroductionCreateRequest(null, List.of(a), List.of(Competency.MOTIVATION),
                "에이회사 지원 동기", "답변")).id();

        long read = version(id);
        McpPrincipal principal = asMcp();
        // 질문을 바꾸는 MCP 수정은 읽은 version 이 필요하고, 연결만 더하는 두 번째 수정은 생략해도 된다(불변 조건 12).
        tools.call("self_intro_update", mcpUpdate(id, "비회사 지원 동기", "답변", List.of(b), List.of("COLLABORATION"), read), principal);
        tools.call("self_intro_update", mcpUpdate(id, "비회사 지원 동기", "답변", List.of(), List.of(), null), principal);

        asWeb();
        var kept = service.findById(id);
        assertThat(kept.question()).isEqualTo("비회사 지원 동기");
        assertThat(kept.positionLinks()).containsExactlyInAnyOrder(
                new PositionLinkView(a, LinkSource.USER), new PositionLinkView(b, LinkSource.AI));
        assertThat(kept.questionTypes()).containsExactlyInAnyOrder(
                new QuestionTypeView(Competency.MOTIVATION, LinkSource.USER), new QuestionTypeView(Competency.COLLABORATION, LinkSource.AI));
        // 에이회사용 연결이 남아 있어 다른 회사 지원에 재사용하지 말라는 표시도 그대로다.
        assertThat(only(agent.list(null, null, null, null, null, null, null, null)).alreadyUsedFor())
                .extracting(SelfIntroductionList.UsedFor::company).containsExactlyInAnyOrder("에이회사", "비회사");

        var cleared = service.update(id, new SelfIntroductionUpdateRequest(List.of(), List.of(), List.of(), "비회사 지원 동기", "답변", version(id)));
        assertThat(cleared.positionLinks()).isEmpty();
        assertThat(cleared.questionTypes()).isEmpty();
    }

    /**
     * 이 직무용 직접 출처가 없으면 빈 배열 대신 연결 이력서로 찾은 답변을 "출처 미상"으로 준다.
     * 다른 직무용으로 쓴 것이 분명한 답변(직접 출처가 있는 답변)은 간접 결과에 섞지 않는다.
     */
    @Test
    void withoutDirectSourceFallsBackToIndirectAndSkipsAnswersWrittenForOthers() {
        signIn();
        UUID backend = position(posting("에이회사", "백엔드"));
        JobPostingResponse other = posting("비회사", "프론트");
        UUID resumeId = resumes.create(new ResumeRequest("에이 지원용", null, List.of(backend), null)).id();
        UUID unknown = service.create(new SelfIntroductionCreateRequest(List.of(resumeId), null, null, "협업 경험", "답변")).id();
        UUID forOther = service.create(new SelfIntroductionCreateRequest(List.of(resumeId), List.of(position(other)), null,
                "비회사 지원 동기", "답변")).id();

        var fallback = agent.list(null, null, backend, null, null, null, null, null);
        assertThat(fallback.fallback()).isEqualTo("indirect");
        var item = only(fallback);
        assertThat(item.id()).isEqualTo(unknown);
        assertThat(item.sourceStatus()).isEqualTo("출처 미상");
        assertThat(item.sources().direct()).isEmpty();
        assertThat(item.sources().indirect()).singleElement().satisfies(indirect -> {
            assertThat(indirect.resume()).isEqualTo("에이 지원용");
            assertThat(indirect.position()).isEqualTo("백엔드");
            assertThat(indirect.company()).isEqualTo("에이회사");
        });

        var direct = agent.list(null, null, null, null, other.companyId(), null, null, null);
        assertThat(direct.fallback()).isNull();
        assertThat(only(direct).id()).isEqualTo(forOther);
        assertThat(only(agent.list(null, null, null, null, null, null, true, null)).id()).isEqualTo(unknown);
        assertThat(only(agent.list(null, null, null, null, null, null, false, null)).id()).isEqualTo(forOther);
        assertThatThrownBy(() -> agent.list(null, null, null, UUID.randomUUID(), null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** MCP 목록은 미리보기·limit·complete 를 주고, 웹 REST 목록은 답변 전문 그대로다(설계 5.1). */
    @Test
    void agentListPreviewsAndLimitsWhileRestListKeepsFullAnswer() {
        signIn();
        UUID resumeId = resumes.create(new ResumeRequest("지원용", null, List.of(), null)).id();
        String longAnswer = "협업 ".repeat(200);
        for (int i = 0; i < 3; i++) {
            service.create(new SelfIntroductionCreateRequest(List.of(resumeId), null, null, "문항 " + i, longAnswer));
        }

        var page = agent.list(null, null, null, null, null, null, null, 2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.complete()).isFalse();
        assertThat(page.results()).hasSize(2).allSatisfy(item -> {
            assertThat(item.answer()).isNull();
            assertThat(item.answerPreview()).hasSize(301).endsWith("…");
            assertThat(item.answerLength()).isEqualTo(longAnswer.trim().length());
        });
        assertThat(listAll().complete()).isTrue();
        assertThat(service.findAll(null, null)).hasSize(3)
                .allSatisfy(response -> assertThat(response.answer()).isEqualTo(longAnswer.trim()));

        var searched = agent.list("협업", null, null, null, null, null, null, null);
        assertThat(searched.results()).allSatisfy(item -> {
            assertThat(item.score()).isPositive();
            assertThat(item.matchedTerms()).containsExactly("협업");
        });
        UUID first = searched.results().get(0).id();
        assertThat(agent.get(first).answer()).isEqualTo(longAnswer.trim());
    }

    /**
     * limit 은 기본 10, 상한 50, 0 이하는 1 이다. 정확히 300자 답변은 말줄임 없이 그대로다.
     * q 는 100자까지 받고 101자는 400 QUERY_TOO_LONG 이며, MCP tools/call 에서도 같은 코드의 도구 오류다(설계 5.3, 7절 2단계).
     */
    @Test
    void listLimitPreviewAndQueryLengthBoundaries() {
        signIn();
        UUID resumeId = resumes.create(new ResumeRequest("지원용", null, List.of(), null)).id();
        for (int i = 0; i < 51; i++) {
            service.create(new SelfIntroductionCreateRequest(List.of(resumeId), null, null, "문항 " + i, "답변"));
        }
        String exact = "가".repeat(300);
        service.create(new SelfIntroductionCreateRequest(List.of(resumeId), null, null, "미리보기 경계", exact));

        var byDefault = listAll();
        assertThat(byDefault.total()).isEqualTo(52);
        assertThat(byDefault.results()).hasSize(10);
        assertThat(byDefault.complete()).isFalse();
        assertThat(agent.list(null, null, null, null, null, null, null, 999).results()).hasSize(50);
        assertThat(agent.list(null, null, null, null, null, null, null, 0).results()).hasSize(1);
        assertThat(only(agent.list("경계", null, null, null, null, null, null, null)).answerPreview()).isEqualTo(exact);

        assertThatCode(() -> agent.list("가".repeat(100), null, null, null, null, null, null, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> agent.list("가".repeat(101), null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("QUERY_TOO_LONG");
                });

        asMcp();
        var response = mcp.rpc(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params",
                Map.of("name", "self_intro_list", "arguments", Map.of("q", "가".repeat(101)))), null);
        Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) response.getBody()).get("result");
        assertThat(result.get("isError")).isEqualTo(true);
        assertThat(result.get("content").toString()).contains("QUERY_TOO_LONG");
    }

    /** 다른 회사용으로 쓴 지원동기는 재사용 금지 표시가 붙는다. 저장하지 않은 문항 유형은 정규식 추정이다(설계 4.1·4.2). */
    @Test
    void fitAnswerWrittenForAnotherCompanyIsFlagged() {
        signIn();
        JobPostingResponse a = posting("에이회사", "백엔드");
        UUID id = service.create(new SelfIntroductionCreateRequest(null, List.of(position(a)), null,
                "에이회사 지원 동기를 쓰세요.", "답변")).id();

        var anywhere = only(listAll());
        assertThat(anywhere.questionTypes()).singleElement().satisfies(type -> {
            assertThat(type.code()).isEqualTo(Competency.MOTIVATION);
            assertThat(type.from()).isEqualTo("REGEX");
            assertThat(type.trust()).isEqualTo("추정");
        });
        assertThat(anywhere.alreadyUsedFor()).singleElement().satisfies(used -> {
            assertThat(used.company()).isEqualTo("에이회사");
            assertThat(used.sameCompany()).isNull();
            assertThat(used.otherCompanySpecific()).isTrue();
        });
        var sameCompany = only(agent.list(null, null, null, null, a.companyId(), null, null, null));
        assertThat(sameCompany.alreadyUsedFor()).singleElement().satisfies(used -> {
            assertThat(used.sameCompany()).isTrue();
            assertThat(used.otherCompanySpecific()).isFalse();
        });
        assertThat(only(agent.list(null, null, null, null, null, Competency.MOTIVATION, null, null)).id()).isEqualTo(id);
        assertThat(agent.list(null, null, null, null, null, Competency.CONFLICT, null, null).results()).isEmpty();
    }

    /**
     * alreadyUsedFor 는 연결마다 같은 회사인지(sameCompany)와 재사용 금지(FIT 이고 다른 회사)를 따로 판정한다.
     * FIT 이 아닌 답변(협업)은 다른 회사용이어도 재사용 금지가 아니다(설계 5.3).
     */
    @Test
    void alreadyUsedForJudgesEachLinkBySameCompanyAndFit() {
        signIn();
        JobPostingResponse a = posting("에이회사", "백엔드");
        JobPostingResponse b = posting("비회사", "프론트");
        List<UUID> both = List.of(position(a), position(b));
        UUID fit = service.create(new SelfIntroductionCreateRequest(null, both, null, "지원 동기를 쓰세요.", "답변")).id();
        UUID collaboration = service.create(new SelfIntroductionCreateRequest(null, both, null, "협업 경험을 쓰세요.", "답변")).id();

        var forB = agent.list(null, null, null, null, b.companyId(), null, null, null);
        Map<UUID, SelfIntroductionList.Item> items = forB.results().stream()
                .collect(Collectors.toMap(SelfIntroductionList.Item::id, Function.identity()));
        assertThat(items).containsOnlyKeys(fit, collaboration);
        assertThat(items.get(fit).alreadyUsedFor())
                .extracting(SelfIntroductionList.UsedFor::company, SelfIntroductionList.UsedFor::sameCompany,
                        SelfIntroductionList.UsedFor::otherCompanySpecific)
                .containsExactlyInAnyOrder(tuple("에이회사", false, true), tuple("비회사", true, false));
        assertThat(items.get(collaboration).alreadyUsedFor())
                .extracting(SelfIntroductionList.UsedFor::company, SelfIntroductionList.UsedFor::sameCompany,
                        SelfIntroductionList.UsedFor::otherCompanySpecific)
                .containsExactlyInAnyOrder(tuple("에이회사", false, false), tuple("비회사", true, false));
    }

    /**
     * AI 가 저장한 문항 유형은 정규식 추정을 지우지 않는다 — 확인 안 된 태그가 재사용 금지 표시를 끄면 안 된다.
     * 사용자가 확정한 유형만 추정보다 앞선다(설계 4.1: 입력값 → 저장된 USER 태그 → 정규식).
     */
    @Test
    void aiQuestionTypeDoesNotHideRegexFitButConfirmedTypeDoes() {
        signIn();
        UUID a = position(posting("에이회사", "백엔드"));
        String question = "에이회사 지원 동기를 쓰세요.";
        UUID id = service.create(new SelfIntroductionCreateRequest(null, List.of(a), null, question, "답변")).id();

        McpPrincipal principal = asMcp();
        tools.call("self_intro_update", mcpUpdate(id, question, "답변", null, List.of("COLLABORATION"), null), principal);

        var tagged = only(listAll());
        assertThat(tagged.questionTypes())
                .extracting(SelfIntroductionList.QuestionType::code, SelfIntroductionList.QuestionType::from)
                .containsExactlyInAnyOrder(tuple(Competency.MOTIVATION, "REGEX"), tuple(Competency.COLLABORATION, "AI"));
        assertThat(tagged.alreadyUsedFor()).singleElement()
                .satisfies(used -> assertThat(used.otherCompanySpecific()).isTrue());
        assertThat(only(agent.list(null, null, null, null, null, Competency.MOTIVATION, null, null)).id()).isEqualTo(id);

        asWeb();
        service.confirm(id, new SelfIntroductionConfirmRequest(null, List.of(Competency.COLLABORATION)));

        var confirmed = only(listAll());
        assertThat(confirmed.questionTypes())
                .extracting(SelfIntroductionList.QuestionType::code, SelfIntroductionList.QuestionType::from)
                .containsExactly(tuple(Competency.COLLABORATION, "USER"));
        assertThat(confirmed.alreadyUsedFor()).singleElement()
                .satisfies(used -> assertThat(used.otherCompanySpecific()).isFalse());
    }

    /**
     * MCP 목록·상세 1회는 계정의 직무 상세(Position·공고·기업 엔티티)와 이력서 본문(content JSONB)을 읽지 않는다.
     * 연결된 직무·이력서의 이름만 가벼운 조회로 읽고, SQL 수는 데이터 양과 무관한 상한 안이다(설계 1.3 P4, 1.6).
     */
    @Test
    void agentReadsOnlyLabelsWithinStatementLimit() {
        signIn();
        JobPostingResponse a = posting("에이회사", "백엔드", "데이터");
        JobPostingResponse b = posting("비회사", "프론트");
        posting("다른회사", "운영");   // 연결되지 않은 직무는 읽지 않아야 한다
        List<UUID> resumeIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            resumeIds.add(resumes.create(new ResumeRequest("이력서 " + i, null, List.of(position(a), position(b)), null)).id());
        }
        UUID id = null;
        for (int i = 0; i < 5; i++) {
            id = service.create(new SelfIntroductionCreateRequest(resumeIds, List.of(position(a)), List.of(Competency.MOTIVATION),
                    "지원 동기 " + i, "답변")).id();
        }
        service.create(new SelfIntroductionCreateRequest(resumeIds, null, null, "협업 경험", "답변"));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        var item = agent.get(id);
        long getStatements = statistics.getPrepareStatementCount();
        assertThat(item.sources().direct()).singleElement().satisfies(direct -> assertThat(direct.company()).isEqualTo("에이회사"));
        assertThat(item.sources().indirect()).hasSize(6).extracting(SelfIntroductionList.Indirect::company)
                .containsOnly("에이회사", "비회사");
        assertNoDetailLoads(statistics);
        assertThat(getStatements).as("self_intro_get 1회의 SQL 수").isLessThanOrEqualTo(MAX_GET_STATEMENTS);

        statistics.clear();
        var list = listAll();
        long listStatements = statistics.getPrepareStatementCount();
        assertThat(list.results()).hasSize(6);
        assertNoDetailLoads(statistics);
        assertThat(listStatements).as("self_intro_list 1회의 SQL 수").isLessThanOrEqualTo(MAX_LIST_STATEMENTS);

        statistics.clear();
        var fallback = agent.list(null, null, position(b), null, null, null, null, null);
        long fallbackStatements = statistics.getPrepareStatementCount();
        assertThat(fallback.fallback()).isEqualTo("indirect");
        assertThat(only(fallback).question()).isEqualTo("협업 경험");
        assertNoDetailLoads(statistics);
        assertThat(fallbackStatements).as("직무 필터 목록 1회의 SQL 수").isLessThanOrEqualTo(MAX_FILTERED_LIST_STATEMENTS);

        statistics.clear();
        var byCompany = agent.list(null, null, null, null, a.companyId(), null, null, null);
        long companyStatements = statistics.getPrepareStatementCount();
        assertThat(byCompany.results()).hasSize(5);
        assertNoDetailLoads(statistics);
        assertThat(companyStatements).as("기업 필터 목록 1회의 SQL 수").isLessThanOrEqualTo(MAX_FILTERED_LIST_STATEMENTS);
    }

    private static void assertNoDetailLoads(Statistics statistics) {
        for (Class<?> type : List.of(Resume.class, Position.class, JobPosting.class, Company.class)) {
            assertThat(statistics.getEntityStatistics(type.getName()).getLoadCount()).as(type.getSimpleName() + " 엔티티 로드").isZero();
        }
    }

    /** 직무가 지워지면 연결도 사라지고(ON DELETE CASCADE), 연결이 0개가 된 문항도 수정할 수 있다. */
    @Test
    void deletedPositionDropsLinkAndUnlinkedAnswerStaysEditable() {
        signIn();
        JobPostingResponse created = posting("에이회사", "백엔드", "데이터");
        UUID backend = created.positions().get(0).id();
        UUID data = created.positions().get(1).id();
        UUID id = service.create(new SelfIntroductionCreateRequest(null, List.of(backend), null, "협업 경험", null)).id();

        postings.update(created.id(), new JobPostingRequest(null, "에이회사", created.title(), null,
                EmploymentType.FULL_TIME, null, ApplicationStatus.INTERESTED, null, null,
                List.of(new PositionNameRequest(data, "데이터")), null));

        assertThat(service.findById(id).positionLinks()).isEmpty();
        var updated = service.update(id, new SelfIntroductionUpdateRequest(List.of(), List.of(), null, "협업 경험", "답변", version(id)));
        assertThat(updated.resumeIds()).isEmpty();
        assertThat(updated.answer()).isEqualTo("답변");
    }

    /**
     * 같은 읽기(version)에서 출발한 두 수정 중 두 번째는 409 이고 첫 번째 내용이 남는다 — AI 와 웹이 같은 답변을 고친다(불변 조건 12).
     * 쓰기 응답에는 새 version 이 실리고, MCP 상세(self_intro_get)의 version 으로 다시 보내면 저장된다.
     * 확정은 연결만 바꾸지만 version 을 올리고 응답에 싣는다 — 확정 전에 읽은 화면의 저장은 409 다.
     */
    @Test
    void secondUpdateFromSameReadIsStaleAndFirstContentStays() {
        signIn();
        UUID backend = position(posting("에이회사", "백엔드"));
        UUID front = position(posting("비회사", "프론트"));
        var created = service.create(new SelfIntroductionCreateRequest(null, List.of(backend), null, "지원 동기", "처음 답변"));
        long read = created.version();
        // 생성 응답의 version 도 저장된 값이다 — MCP 가 생성 직후 그 값으로 고칠 수 있어야 한다.
        assertThat(read).isEqualTo(version(created.id()));

        var first = service.update(created.id(), new SelfIntroductionUpdateRequest(List.of(), null, null,
                "지원 동기", "웹에서 고친 답변", read));
        assertThat(first.version()).isGreaterThan(read);

        McpPrincipal principal = asMcp();
        assertThatThrownBy(() -> tools.call("self_intro_update",
                mcpUpdate(created.id(), "지원 동기", "AI 가 고친 답변", null, null, read), principal))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SELF_INTRO_STALE");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThatThrownBy(() -> tools.call("self_intro_update",
                mcpUpdate(created.id(), "지원 동기", "AI 가 고친 답변", null, null, null), principal))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("VERSION_REQUIRED"));
        asWeb();
        assertThatThrownBy(() -> service.update(created.id(), new SelfIntroductionUpdateRequest(List.of(), null, null,
                "지원 동기", "다른 탭의 답변", read)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SELF_INTRO_STALE"));
        assertThat(service.findById(created.id()).answer()).isEqualTo("웹에서 고친 답변");

        asMcp();
        long latest = agent.get(created.id()).version();
        assertThat(latest).isEqualTo(first.version());
        var saved = (SelfIntroductionResponse) tools.call("self_intro_update",
                mcpUpdate(created.id(), "지원 동기", "AI 가 고친 답변", List.of(front), null, latest), principal);
        assertThat(saved.version()).isGreaterThan(latest);
        assertThat(only(listAll()).version()).isEqualTo(saved.version());

        asWeb();
        var confirmed = service.confirm(created.id(), new SelfIntroductionConfirmRequest(List.of(front), null));
        assertThat(confirmed.version()).isGreaterThan(saved.version()).isEqualTo(version(created.id()));
        assertThatThrownBy(() -> service.update(created.id(), new SelfIntroductionUpdateRequest(List.of(), null, null,
                "지원 동기", "확정 전에 읽은 화면의 답변", saved.version())))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SELF_INTRO_STALE"));
        assertThat(service.findById(created.id()).answer()).isEqualTo("AI 가 고친 답변");
    }

    /**
     * 직무·공고·기업 상세는 지우면 직접 출처가 CASCADE 로 사라질 내 자소서 수를 싣는다(삭제 확인창용). 한 문항이 같은 공고의
     * 두 직무에 연결돼 있어도 한 번만 센다. 기업은 소속 공고 전부를 센다. 목록 응답은 null 이고 남의 자소서는 세지 않는다.
     */
    @Test
    void detailCountsSelfIntrosWhoseDirectSourceADeleteWouldDrop() {
        UUID other = signIn();
        JobPostingResponse theirs = posting("남의 회사", "남의 직무");
        service.create(new SelfIntroductionCreateRequest(null, List.of(position(theirs)), null, "남의 답변", null));

        UUID me = signIn();
        JobPostingResponse a = posting("에이회사", "백엔드", "데이터");
        UUID backend = a.positions().get(0).id();
        UUID data = a.positions().get(1).id();
        UUID front = position(posting("에이회사", "프론트"));
        JobPostingResponse b = posting("비회사", "기획");
        service.create(new SelfIntroductionCreateRequest(null, List.of(backend, data), null, "두 직무용", null));
        service.create(new SelfIntroductionCreateRequest(null, List.of(data), null, "데이터용", null));
        service.create(new SelfIntroductionCreateRequest(null, List.of(front), null, "프론트용", null));
        service.create(new SelfIntroductionCreateRequest(null, List.of(position(b)), null, "비회사용", null));

        assertThat(positionService.findById(backend).linkedSelfIntroCount()).isEqualTo(1);
        assertThat(positionService.findById(data).linkedSelfIntroCount()).isEqualTo(2);
        assertThat(postings.findById(a.id()).linkedSelfIntroCount()).isEqualTo(2);
        assertThat(companies.findById(a.companyId()).linkedSelfIntroCount()).isEqualTo(3);
        assertThat(companies.findById(b.companyId()).linkedSelfIntroCount()).isEqualTo(1);
        assertThat(positionService.findAll(null)).extracting(PositionResponse::linkedSelfIntroCount).containsOnlyNulls();
        assertThat(companies.findAll()).extracting(CompanyResponse::linkedSelfIntroCount).containsOnlyNulls();

        McpPrincipal principal = asMcp();
        assertThat(((PositionResponse) tools.call("position_get", Map.of("id", data.toString()), principal))
                .linkedSelfIntroCount()).isEqualTo(2);
        assertThat(((JobPostingResponse) tools.call("posting_get", Map.of("id", a.id().toString()), principal))
                .linkedSelfIntroCount()).isEqualTo(2);
        assertThat(((CompanyResponse) tools.call("company_get", Map.of("id", a.companyId().toString()), principal))
                .linkedSelfIntroCount()).isEqualTo(3);

        // 남의 직무·공고·기업 id 를 넣어도 소유자 조건이 남의 자소서를 세지 않는다. 그 계정으로는 1이다.
        assertThat(positionRepository.countSelfIntrosWrittenFor(position(theirs), me)).isZero();
        assertThat(positionRepository.countSelfIntrosWrittenForPosting(theirs.id(), me)).isZero();
        assertThat(positionRepository.countSelfIntrosWrittenForCompany(theirs.companyId(), me)).isZero();
        assertThat(positionRepository.countSelfIntrosWrittenForCompany(theirs.companyId(), other)).isEqualTo(1);
        asWeb();
        assertThatThrownBy(() -> positionService.findById(position(theirs))).isInstanceOf(ResourceNotFoundException.class);
    }

    /** 문항 유형 코드는 시스템 고정 어휘다. 표와 enum 이 같아야 기동한다(V23). */
    @Test
    void competencyCatalogMatchesEnum() {
        assertThat(jdbc.queryForList("select code from competencies order by sort_order", String.class))
                .containsExactlyElementsOf(Arrays.stream(Competency.values()).map(Enum::name).toList());
    }
}
