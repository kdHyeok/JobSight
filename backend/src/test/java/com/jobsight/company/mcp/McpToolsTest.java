package com.jobsight.company.mcp;

import com.jobsight.company.admin.AdminController;
import com.jobsight.company.admin.AdminStatsService;
import com.jobsight.company.auth.*;
import com.jobsight.company.company.*;
import com.jobsight.company.company.dto.CompanyRequest;
import com.jobsight.company.companycontent.*;
import com.jobsight.company.position.*;
import com.jobsight.company.posting.*;
import com.jobsight.company.reference.*;
import com.jobsight.company.resume.*;
import com.jobsight.company.selfintro.*;
import com.jobsight.company.setting.AppSettingService;
import com.jobsight.company.user.AppUserService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolsTest {
    CompanyService companies = mock(CompanyService.class);
    JobPostingService postings = mock(JobPostingService.class);
    PositionService positions = mock(PositionService.class);
    ReferenceService references = mock(ReferenceService.class);
    CompanyContentService contents = mock(CompanyContentService.class);
    ResumeService resumes = mock(ResumeService.class);
    SelfIntroductionService selfIntroductions = mock(SelfIntroductionService.class);
    SelfIntroductionAgentService selfIntroAgent = mock(SelfIntroductionAgentService.class);
    AppUserService users = mock(AppUserService.class);
    AppSettingService settings = mock(AppSettingService.class);
    AdminStatsService stats = mock(AdminStatsService.class);
    com.jobsight.company.experience.ExperienceService experiences = mock(com.jobsight.company.experience.ExperienceService.class);
    com.jobsight.company.experience.ExperienceSearch experienceSearch = mock(com.jobsight.company.experience.ExperienceSearch.class);
    com.jobsight.company.experience.ExperienceBankService bank = mock(com.jobsight.company.experience.ExperienceBankService.class);
    CurrentUser current = new CurrentUser();
    McpTools tools;
    McpPrincipal principal = new McpPrincipal(UUID.randomUUID(), Set.copyOf(McpOAuthConfig.SCOPES));

    @BeforeEach void setUp() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        tools = new McpTools(new CompanyController(companies), new JobPostingController(postings),
                new PositionController(positions), new ReferenceController(references), new CompanyContentController(contents),
                new ResumeController(resumes), new SelfIntroductionController(selfIntroductions),
                new AdminController(users, settings, stats, current), new AuthController(users, settings, current, null, null),
                new McpActions(current, users, positions, references, contents, resumes, selfIntroAgent, experiences,
                        experienceSearch, bank, JsonMapper.builder().findAndAddModules().build()),
                JsonMapper.builder().findAndAddModules().build(), validator);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void catalogScopesAndAdministratorRoleAreEnforced() {
        assertThat(tools.list(principal)).hasSize(50);
        assertThat(tools.list(principal)).extracting(tool -> tool.get("name")).contains(
                "resume_list", "resume_get", "resume_create", "resume_update", "resume_copy", "resume_delete",
                "self_intro_list", "self_intro_get", "self_intro_create", "self_intro_update", "self_intro_delete",
                "experience_index", "experience_search");
        assertThat(tools.list(new McpPrincipal(principal.userId(), Set.of(McpOAuthConfig.READ))))
                .allSatisfy(tool -> assertThat(((Map<?, ?>) tool.get("annotations")).get("readOnlyHint")).isEqualTo(true));
        assertThatThrownBy(() -> tools.call("admin_users_list", Map.of(), principal))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> tools.call("company_delete", Map.of("id", UUID.randomUUID().toString()),
                new McpPrincipal(principal.userId(), Set.of(McpOAuthConfig.READ))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(current.id()).isEqualTo(principal.userId());
    }
    @Test void validDtoReachesExistingControllerAndInvalidDataNeverDoes() {
        tools.call("company_create", Map.of("request", Map.of("name", "테스트", "benefits", "유연근무",
                "annualRevenue", 2500000000L, "revenueUnit", "HUNDRED_MILLION")), principal);
        verify(companies).create(argThat(r -> r.name().equals("테스트") && r.annualRevenue() == 2500000000L));
        clearInvocations(companies);
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("name", ""), Map.of("name", "테스트", "websiteUrl", "javascript:alert(1)"),
                Map.of("name", "테스트", "ownerId", UUID.randomUUID().toString()), Map.of("name", "테스트", "annualRevenue", 1.5),
                Map.of("name", "테스트", "employeeCount", -1), Map.of("name", "테스트", "industries", List.of("x".repeat(61))))) {
            assertThatThrownBy(() -> tools.call("company_create", Map.of("request", bad), principal)).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(companies);
    }
    @Test void resumeAndSelfIntroductionInputsReachServices() {
        UUID resumeId = UUID.randomUUID();
        when(resumes.create(any())).thenReturn(new com.jobsight.company.resume.dto.ResumeResponse(resumeId,
                "플러그인 테스트 이력서", ResumeContent.empty(), List.of(), 0L, Map.of(),
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, List.of()));
        tools.call("resume_create", Map.of("request", Map.of(
                "name", "플러그인 테스트 이력서",
                "content", Map.of("basic", Map.of("name", "테스트 사용자")),
                "positionIds", List.of())), principal);
        verify(resumes).create(argThat(request -> request.name().equals("플러그인 테스트 이력서")
                && request.content().basic().name().equals("테스트 사용자")));

        when(selfIntroductions.create(any())).thenReturn(new com.jobsight.company.selfintro.dto.SelfIntroductionResponse(
                UUID.randomUUID(), List.of(resumeId), "지원 동기", "협업 경험", java.time.Instant.EPOCH, java.time.Instant.EPOCH, List.of(), List.of(), 0L, List.of()));
        tools.call("self_intro_create", Map.of("request", Map.of(
                "resumeIds", List.of(resumeId.toString()), "question", "지원 동기", "answer", "협업 경험")), principal);
        tools.call("self_intro_list", Map.of("q", "협업", "resumeId", resumeId.toString()), principal);

        verify(selfIntroductions).create(argThat(request -> request.resumeIds().equals(List.of(resumeId))
                && request.question().equals("지원 동기") && request.answer().equals("협업 경험")));
        verify(selfIntroAgent).list("협업", resumeId, null, null, null, null, null, null);
        verify(selfIntroductions, never()).findAll(any(), any());
    }

    /**
     * self_intro_list·get 은 웹 REST 목록을 바꾸지 않도록 McpActions 래퍼로 간다(설계 5.1·5.3).
     * 필터와 문항 유형 enum, q 의 100자 제한이 스키마에 드러나고, 직무·문항 유형 연결은 생성 요청 그대로 서비스에 닿는다.
     */
    @Test void selfIntroToolsUseAgentViewAndCarryLinks() {
        UUID positionId = UUID.randomUUID(), companyId = UUID.randomUUID(), id = UUID.randomUUID();
        tools.call("self_intro_list", Map.of("positionId", positionId.toString(), "companyId", companyId.toString(),
                "questionType", "MOTIVATION", "unlinked", false, "limit", 5), principal);
        verify(selfIntroAgent).list(null, null, positionId, null, companyId, Competency.MOTIVATION, false, 5);
        tools.call("self_intro_get", Map.of("id", id.toString()), principal);
        verify(selfIntroAgent).get(id);
        verifyNoInteractions(selfIntroductions);

        when(selfIntroductions.create(any())).thenReturn(new com.jobsight.company.selfintro.dto.SelfIntroductionResponse(
                id, List.of(), "지원 동기", null, java.time.Instant.EPOCH, java.time.Instant.EPOCH, List.of(), List.of(), 0L, List.of()));
        tools.call("self_intro_create", Map.of("request", Map.of("positionIds", List.of(positionId.toString()),
                "questionTypes", List.of("MOTIVATION", "ASPIRATION"), "question", "지원 동기")), principal);
        verify(selfIntroductions).create(argThat(request -> request.resumeIds() == null
                && request.positionIds().equals(List.of(positionId))
                && request.questionTypes().equals(List.of(Competency.MOTIVATION, Competency.ASPIRATION))));
        // 이력서도 직무도 없는 생성은 서비스에 닿기 전에 거부된다.
        assertThatThrownBy(() -> tools.call("self_intro_create", Map.of("request", Map.of("question", "지원 동기")), principal))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("linked");

        var list = tools.list(principal).stream().filter(t -> t.get("name").equals("self_intro_list")).findFirst().orElseThrow();
        String schema = JsonMapper.builder().build().writeValueAsString(list.get("inputSchema"));
        assertThat(schema).contains("\"maxLength\":100", "\"positionId\"", "\"postingId\"", "\"companyId\"",
                "\"unlinked\"", "\"limit\"", "\"PERSONALITY\"");
        // 확정은 웹에서만 한다 — MCP 에는 확정 도구가 없다(설계 3절 규칙 3).
        assertThat(tools.list(principal)).extracting(t -> t.get("name").toString()).noneMatch(name -> name.contains("confirm"));
    }
    @Test void defaultsAndRelationshipArgumentsArePreserved() {
        tools.call("posting_list", Map.of(), principal);
        verify(postings).findOpen();
        tools.call("posting_list", Map.of("archived", true), principal);
        verify(postings).findArchived();
        UUID id = UUID.randomUUID(), reference = UUID.randomUUID();
        tools.call("position_replace_references", Map.of("id", id.toString(), "request", Map.of("referenceIds", List.of(reference.toString()))), principal);
        verify(positions).replaceReferences(eq(id), argThat(r -> r.referenceIds().equals(List.of(reference))));
        tools.call("company_content_delete", Map.of("companyId", id.toString(), "id", reference.toString()), principal);
        verify(contents).delete(id, reference);
    }
    /** posting_update 는 빠진 positions·steps 를 null(유지) 그대로 넘기고, "전체 교체" 안내를 붙이지 않는다(설계 5.3). */
    @Test void postingUpdateKeepsOmittedListsAsNull() {
        UUID id = UUID.randomUUID();
        tools.call("posting_update", Map.of("id", id.toString(), "request", Map.of("companyName", "회사", "title", "공채",
                "employmentType", "FULL_TIME", "status", "INTERESTED")), principal);
        verify(postings).update(eq(id), argThat(r -> r.positions() == null && r.steps() == null));
        var update = tools.list(principal).stream().filter(t -> t.get("name").equals("posting_update")).findFirst().orElseThrow();
        assertThat(update.get("description").toString()).doesNotContain("전체 교체").contains("null", "이름");
    }
    /** 행 도구는 섹션 enum·0-based index·expectedVersion 을 그대로 서비스에 넘긴다. 추가는 버전이 선택이다. */
    @Test void resumeRowToolsPassSectionAndIndex() {
        UUID id = UUID.randomUUID();
        tools.call("resume_row_add", Map.of("id", id.toString(), "section", "certificates",
                "request", Map.of("name", "SQLD", "acquiredYm", "2026.03")), principal);
        verify(resumes).addRow(eq(id), eq(ResumeSection.certificates), argThat(r -> r.name().equals("SQLD") && r.school() == null), isNull());
        tools.call("resume_row_delete", Map.of("id", id.toString(), "section", "projects", "index", 2, "expectedVersion", 7), principal);
        verify(resumes).deleteRow(id, ResumeSection.projects, 2, null, 7L);
        assertThatThrownBy(() -> tools.call("resume_row_delete", Map.of("id", id.toString(), "section", "nope", "index", 0), principal))
                .isInstanceOf(RuntimeException.class);
        var schema = JsonMapper.builder().build().writeValueAsString(tools.list(principal).stream()
                .filter(t -> t.get("name").equals("resume_row_add")).findFirst().orElseThrow().get("inputSchema"));
        for (var section : ResumeSection.values()) assertThat(schema).contains("\"" + section.name() + "\"");
    }

    /** 행 번호로 고치거나 지울 때는 버전이 필수다 — 예전에 읽은 번호가 다른 행을 가리킬 수 있다(docs/resumes.md 불변 조건 8). */
    @Test void resumeIndexToolsRequireExpectedVersion() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> tools.call("resume_row_delete", Map.of("id", id.toString(), "section", "projects", "index", 0), principal))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expectedVersion");
        var update = tools.list(principal).stream().filter(t -> t.get("name").equals("resume_row_update")).findFirst().orElseThrow();
        assertThat(((Map<?, ?>) update.get("inputSchema")).get("required").toString()).contains("expectedVersion");
        var add = tools.list(principal).stream().filter(t -> t.get("name").equals("resume_row_add")).findFirst().orElseThrow();
        assertThat(((Map<?, ?>) add.get("inputSchema")).get("required").toString()).doesNotContain("expectedVersion");
        // 이력서 저장은 null=유지라 다른 update 도구에 붙는 "전체 교체" 안내가 붙으면 안 된다.
        var resumeUpdate = tools.list(principal).stream().filter(t -> t.get("name").equals("resume_update")).findFirst().orElseThrow();
        assertThat(resumeUpdate.get("description").toString()).doesNotContain("전체 교체").contains("expectedVersion");
    }

    @Test void schemaContainsWholeCompanyDtoAndStatusEnums() {
        var company = tools.list(principal).stream().filter(t -> t.get("name").equals("company_create")).findFirst().orElseThrow();
        String schema = JsonMapper.builder().build().writeValueAsString(company.get("inputSchema"));
        for (var field : CompanyRequest.class.getRecordComponents()) assertThat(schema).contains("\"" + field.getName() + "\"");
        var status = tools.list(principal).stream().filter(t -> t.get("name").equals("posting_set_status")).findFirst().orElseThrow();
        String statusSchema = JsonMapper.builder().build().writeValueAsString(status.get("inputSchema"));
        for (var value : ApplicationStatus.values()) assertThat(statusSchema).contains(value.name());
    }
}
