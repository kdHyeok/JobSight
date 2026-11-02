package com.jobsight.company.selfintro;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.posting.ApplicationStatus;
import com.jobsight.company.posting.EmploymentType;
import com.jobsight.company.posting.JobPostingService;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.posting.dto.JobPostingResponse;
import com.jobsight.company.posting.dto.PositionNameRequest;
import com.jobsight.company.resume.ResumeService;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionConfirmRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionCreateRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionList;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse;
import com.jobsight.company.selfintro.dto.SelfIntroductionUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 자소서 직접 출처(written_for)와 문항 유형을 실제 복합 FK 로 본다 — 설계 2.4·2.5, 7절 2단계.
 * 테스트마다 새 계정을 만들어 목록이 서로 섞이지 않게 한다.
 */
@Testcontainers
@SpringBootTest
class SelfIntroductionRelationsIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean CurrentUser currentUser;
    @Autowired SelfIntroductionService service;
    @Autowired SelfIntroductionAgentService agent;
    @Autowired JobPostingService postings;
    @Autowired ResumeService resumes;
    @Autowired JdbcTemplate jdbc;

    /** 새 계정을 만들고 그 계정으로 요청한다. */
    private UUID signIn() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now())", id, id + "@example.com", "sub-" + id);
        given(currentUser.id()).willReturn(id);
        given(currentUser.viaMcp()).willReturn(false);
        return id;
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
        assertThatThrownBy(() -> service.update(id, new SelfIntroductionUpdateRequest(List.of(), List.of(foreign), null, "지원 동기", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.findById(id).positionLinks()).extracting(SelfIntroductionResponse.PositionLinkView::positionId)
                .containsExactly(mine);
        assertThatThrownBy(() -> jdbc.update("insert into self_introduction_positions "
                + "(owner_id, self_introduction_id, position_id, source) values (?, ?, ?, 'USER')", owner, id, foreign))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** MCP 가 남긴 연결은 "AI 기록"으로 보이고, 웹의 확정 뒤에 "확정"이 된다(설계 3절 규칙 2·3·5). */
    @Test
    void aiLinkIsListedAsAiRecordUntilWebConfirms() {
        signIn();
        UUID backend = position(posting("에이회사", "백엔드"));
        given(currentUser.viaMcp()).willReturn(true);
        UUID id = service.create(new SelfIntroductionCreateRequest(null, List.of(backend), List.of(Competency.MOTIVATION),
                "에이회사 지원 동기", "답변")).id();

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

        given(currentUser.viaMcp()).willReturn(false);
        service.confirm(id, new SelfIntroductionConfirmRequest(List.of(backend), List.of(Competency.MOTIVATION)));

        var confirmed = only(agent.list(null, null, backend, null, null, null, null, null));
        assertThat(confirmed.sources().direct()).extracting(SelfIntroductionList.Direct::trust).containsExactly("확정");
        assertThat(confirmed.questionTypes()).extracting(SelfIntroductionList.QuestionType::trust).containsExactly("확정");
        assertThat(jdbc.queryForObject("select source from self_introduction_positions where self_introduction_id = ?",
                String.class, id)).isEqualTo("USER");
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
        assertThat(agent.list(null, null, null, null, null, null, null, null).complete()).isTrue();
        assertThat(service.findAll(null, null)).hasSize(3)
                .allSatisfy(response -> assertThat(response.answer()).isEqualTo(longAnswer.trim()));

        var searched = agent.list("협업", null, null, null, null, null, null, null);
        assertThat(searched.results()).allSatisfy(item -> {
            assertThat(item.score()).isPositive();
            assertThat(item.matchedTerms()).containsExactly("협업");
        });
        UUID first = searched.results().get(0).id();
        assertThat(agent.get(first).answer()).isEqualTo(longAnswer.trim());
        assertThatThrownBy(() -> agent.list("가".repeat(101), null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("QUERY_TOO_LONG"));
    }

    /** 다른 회사용으로 쓴 지원동기는 재사용 금지 표시가 붙는다. 저장하지 않은 문항 유형은 정규식 추정이다(설계 4.1·4.2). */
    @Test
    void fitAnswerWrittenForAnotherCompanyIsFlagged() {
        signIn();
        JobPostingResponse a = posting("에이회사", "백엔드");
        UUID id = service.create(new SelfIntroductionCreateRequest(null, List.of(position(a)), null,
                "에이회사 지원 동기를 쓰세요.", "답변")).id();

        var anywhere = only(agent.list(null, null, null, null, null, null, null, null));
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
        var updated = service.update(id, new SelfIntroductionUpdateRequest(List.of(), List.of(), null, "협업 경험", "답변"));
        assertThat(updated.resumeIds()).isEmpty();
        assertThat(updated.answer()).isEqualTo("답변");
    }

    /** 문항 유형 코드는 시스템 고정 어휘다. 표와 enum 이 같아야 기동한다(V23). */
    @Test
    void competencyCatalogMatchesEnum() {
        assertThat(jdbc.queryForList("select code from competencies order by sort_order", String.class))
                .containsExactlyElementsOf(Arrays.stream(Competency.values()).map(Enum::name).toList());
    }
}
