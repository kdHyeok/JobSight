package com.jobsight.company.experience;

import com.jobsight.company.auth.AppSessionUser;
import com.jobsight.company.common.ApiErrors;
import com.jobsight.company.common.OwnerLock;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.experience.dto.ExperienceFacts;
import com.jobsight.company.experience.dto.ExperienceRequests;
import com.jobsight.company.experience.dto.ExperienceSearchRequest;
import com.jobsight.company.experience.dto.ExperienceSearchResult;
import com.jobsight.company.experience.dto.ExperienceViews;
import com.jobsight.company.mcp.McpOAuthConfig;
import com.jobsight.company.mcp.McpPrincipal;
import com.jobsight.company.mcp.McpTools;
import com.jobsight.company.resume.ResumeContent;
import com.jobsight.company.resume.ResumeService;
import com.jobsight.company.resume.ResumeWriter;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.resume.dto.ResumeResponse;
import com.jobsight.company.selfintro.SelfIntroductionService;
import com.jobsight.company.selfintro.dto.ExperienceRef;
import com.jobsight.company.selfintro.dto.SelfIntroductionCreateRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionList;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse;
import com.jobsight.company.selfintro.dto.SelfIntroductionUpdateRequest;
import com.jobsight.company.user.UserRole;
import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.common.GlobalExceptionHandler;
import com.jobsight.company.experience.dto.ExperienceDossier;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4단계(최소판) 경험 원본·개정·연결 — 설계 7절 4a 검증 칸을 실제 PostgreSQL 스키마(V25)와 실제 트랜잭션으로 본다.
 * 테스트마다 새 계정을 만든다. 웹 요청은 AppSessionUser(출처 USER), MCP 는 McpPrincipal(출처 AI) — 설계 3절 규칙 2.
 */
@Testcontainers
@SpringBootTest
class ExperienceBankIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired McpTools tools;
    @Autowired ResumeService resumes;
    @Autowired ExperienceBankService bank;
    @Autowired ExperienceImportService imports;
    @Autowired ExperienceService views;
    @Autowired ExperienceSearch search;
    @Autowired SelfIntroductionService selfIntros;
    @Autowired ResumeWriter writer;
    @Autowired OwnerLock ownerLock;
    /** 실제 동작 그대로의 spy. 삭제 경합 테스트만 사용 중 확인 질의를 비워 "확인 뒤에 생긴 연결"을 만든다. */
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired Validator validator;
    @Autowired ExperienceController controller;

    private UUID owner;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private UUID signIn() {
        owner = newUser();
        asWeb();
        return owner;
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now())", id, id + "@example.com", "sub-" + id);
        return id;
    }

    private void asWeb() {
        authenticate(new AppSessionUser(owner, owner + "@example.com", UserRole.USER));
    }

    private McpPrincipal asMcp() {
        McpPrincipal principal = new McpPrincipal(owner, Set.copyOf(McpOAuthConfig.SCOPES));
        authenticate(principal);
        return principal;
    }

    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static ExperienceFacts.Item item(String id, String text) {
        return new ExperienceFacts.Item(id, text, List.of());
    }

    private static ExperienceFacts facts(String role, List<ExperienceFacts.Item> actions, List<ExperienceFacts.Item> results,
                                         List<ExperienceFacts.Evidence> evidence) {
        return new ExperienceFacts("2025.03~2025.06", role, null, actions, results, evidence, null, null);
    }

    private static ExperienceFacts.Evidence note(String id, String quote) {
        return new ExperienceFacts.Evidence(id, ExperienceFacts.SourceType.NOTE, null, quote, null, null, null, null);
    }

    private ExperienceViews.Saved create(ExperienceKind kind, String title, String startYm, ExperienceFacts facts,
                                         List<String> skills) {
        return bank.save(null, new ExperienceRequests.Save(kind, title, startYm, null, facts, null, skills, null, null));
    }

    private ExperienceViews.Saved saveFacts(UUID id, long version, ExperienceFacts facts) {
        return bank.save(id, new ExperienceRequests.Save(null, null, null, null, facts, null, null, null, version));
    }

    private UUID resume(String name, Map<String, Object> content) {
        return resumes.create(new ResumeRequest(name, mapper.convertValue(content, ResumeContent.class), List.of(), null)).id();
    }

    private ResumeResponse read(UUID resumeId) {
        return resumes.findById(resumeId);
    }

    private ResumeResponse put(UUID resumeId, ResumeContent content, List<UUID> refresh) {
        ResumeResponse current = read(resumeId);
        return resumes.update(resumeId, new ResumeRequest(current.name(), content, null, current.version(), refresh));
    }

    private static String code(Throwable thrown) {
        return ApiErrors.translate(thrown).code();
    }

    private static String sqlState(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    private List<Map<String, Object>> links(UUID resumeId) {
        return jdbc.queryForList("select experience_id, based_on_revision from resume_experiences where resume_id = ?", resumeId);
    }

    private ExperienceViews.Detail detail(UUID id) {
        return views.detail(id);
    }

    // ── 스키마 ─────────────────────────────────────────────────────────────

    /**
     * 2.3: 계정 삭제는 지연 FK 셋이 있어도 통과해야 한다. 계정 → 경험·이력서·자소서 CASCADE, 연결 → 경험·개정 NO ACTION 인
     * 실제 스키마로 다시 본다. 같은 연결이 있는 경험의 단독 삭제는 커밋 때 막힌다(23503 → 409 EXPERIENCE_IN_USE).
     */
    @Test
    void accountDeletionCascadesThroughDeferredForeignKeys() {
        signIn();
        UUID experience = create(ExperienceKind.PROJECT, "정산 배치", "2025.03",
                facts("백엔드", List.of(item(null, "배치 분할")), List.of(), List.of()), List.of("Kafka")).id();
        resume("백엔드 A", Map.of("projects", List.of(Map.of("name", "정산 배치", "experienceId", experience.toString()))));
        selfIntros.create(new SelfIntroductionCreateRequest(null, null, null, "문제 해결", "답변",
                List.of(new ExperienceRef(experience, 1))));
        assertThat(jdbc.queryForObject("select count(*) from resume_experiences where owner_id = ?", Integer.class, owner)).isOne();

        // 경험 단독 삭제: 지연 FK 라 문장은 통과하고 커밋에서 실패한다. 공용 변환은 409 EXPERIENCE_IN_USE.
        Throwable direct = catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                jdbc.update("delete from experiences where id = ?", experience)));
        assertThat(direct).isNotNull();
        assertThat(code(direct)).isEqualTo("EXPERIENCE_IN_USE");

        jdbc.update("delete from app_users where id = ?", owner);
        for (String table : List.of("experiences", "experience_revisions", "resume_experiences", "self_introduction_experiences",
                "resumes", "self_introductions")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where owner_id = ?", Integer.class, owner))
                    .as(table).isZero();
        }
    }

    /** TEXT[] 매핑(skills·domains)이 validate 를 통과하고 정규화 키로 왕복한다. facts 는 JSON 객체여야 한다(CHECK). */
    @Test
    void skillsAreNormalizedKeysAndFactsMustBeAnObject() {
        signIn();
        ExperienceViews.Saved saved = create(ExperienceKind.PROJECT, "메시지 큐", null, null, List.of("k8s", "Spring Boot", "K8S"));
        assertThat(saved.normalizedSkills()).containsExactly("kubernetes", "springboot");
        assertThat(detail(saved.id()).skills()).containsExactly("kubernetes", "springboot");
        assertThat(saved.changeKind()).isEqualTo("INIT");
        assertThat(saved.revision()).isOne();
        assertThatThrownBy(() -> jdbc.update("update experiences set facts = '[]'::jsonb where id = ?", saved.id()))
                .hasMessageContaining("experiences_facts_object_ck");
    }

    // ── 링커 ───────────────────────────────────────────────────────────────

    /**
     * 키가 없는 행은 (kind, title_key) 가 정확히 1건이고 시작 연월이 충돌하지 않을 때만 연결한다. 2건이면 미연결로 남는다.
     * 같은 문서를 다시 저장해도 표가 같다(멱등). JSON↔표 대조는 0건, rebuild 도 같은 결과다.
     */
    @Test
    void linkerAutoLinksOnlyUniqueTitleAndIsIdempotent() {
        signIn();
        UUID solo = create(ExperienceKind.PROJECT, "Planetory", "2024.03", null, null).id();
        create(ExperienceKind.PROJECT, "JOBIS", "2024.01", null, null);
        create(ExperienceKind.PROJECT, "JOBIS", "2025.01", null, null);
        UUID resumeId = resume("v1", Map.of("projects", List.of(
                Map.of("name", "planetory", "startYm", "2024.03"),
                Map.of("name", "JOBIS", "startYm", "2024.01"),
                Map.of("name", "Planetory", "startYm", "2023.01"))));

        List<ResumeContent.Project> rows = read(resumeId).content().projects();
        assertThat(rows.get(0).experienceId()).isEqualTo(solo);
        assertThat(rows.get(0).experienceRevision()).isOne();
        assertThat(rows.get(1).experienceId()).as("제목 키가 2건 맞으면 미연결").isNull();
        assertThat(rows.get(2).experienceId()).as("시작 연월이 충돌하면 미연결").isNull();
        assertThat(links(resumeId)).hasSize(1);

        put(resumeId, read(resumeId).content(), null);
        put(resumeId, read(resumeId).content(), null);
        assertThat(links(resumeId)).hasSize(1);
        assertThat(writer.mismatches(owner)).isZero();

        jdbc.update("delete from resume_experiences where owner_id = ?", owner);
        assertThat(writer.mismatches(owner)).isOne();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ownerLock.acquire(owner);
            writer.rebuild(owner);
            writer.rebuild(owner);
        });
        assertThat(writer.mismatches(owner)).isZero();
        assertThat(links(resumeId)).hasSize(1);
    }

    /**
     * 6.2 앵커 이어받기·개정 검사. 개정 없이 온 행은 저장된 개정을 잇고(정정 뒤에도 지금 개정으로 바뀌지 않는다), 두 키가 빠진
     * 행은 같은 묶음 키의 저장 앵커를 잇는다. 클라이언트가 바꾼 개정 → 400, 없는 개정 → 404, refreshExperienceIds 만 올린다.
     */
    @Test
    void anchorsAreInheritedAndOnlyRefreshRaisesRevision() {
        signIn();
        ExperienceViews.Saved saved = create(ExperienceKind.PROJECT, "정산 배치", "2025.03",
                facts("백엔드", List.of(item("a1", "배치 분할")), List.of(item("r1", "40분 → 6분")), List.of()), null);
        UUID id = saved.id();
        UUID resumeId = resume("v1", Map.of("projects", List.of(Map.of("name", "정산 배치", "startYm", "2025.03",
                "description", "기업용 문구", "experienceId", id.toString()))));
        assertThat(read(resumeId).content().projects().get(0).experienceRevision()).isOne();

        // 원본 정정(개정 2). 과거 이력서 행의 문구와 개정 번호는 그대로다.
        ExperienceViews.Saved corrected = saveFacts(id, saved.version(),
                facts("백엔드", List.of(item("a1", "배치 분할")), List.of(item("r1", "40분 → 8분")), List.of()));
        assertThat(corrected.changeKind()).isEqualTo("CORRECT");
        assertThat(corrected.needsReview()).extracting(ExperienceViews.NeedsReview::type).containsExactly("RESUME");
        ResumeContent.Project row = read(resumeId).content().projects().get(0);
        assertThat(row.description()).isEqualTo("기업용 문구");
        assertThat(row.experienceRevision()).isOne();
        assertThat(detail(id).rows()).singleElement().satisfies(ref -> assertThat(ref.needsReview()).isTrue());

        // 개정 없이 experienceId 만 보내면 저장된 개정(1)을 잇는다 — 지금 개정(2)으로 바뀌지 않는다.
        ResumeContent onlyId = mapper.convertValue(Map.of("projects", List.of(Map.of("name", "정산 배치", "startYm", "2025.03",
                "description", "고친 문구", "experienceId", id.toString()))), ResumeContent.class);
        assertThat(put(resumeId, onlyId, null).content().projects().get(0).experienceRevision()).isOne();

        // 두 키가 모두 빠진 행은 같은 묶음 키의 저장 앵커를 잇는다(앵커를 모르는 구 번들 웹·AI).
        ResumeContent bare = mapper.convertValue(Map.of("projects", List.of(Map.of("name", "정산 배치", "startYm", "2025.03",
                "description", "구 번들 저장"))), ResumeContent.class);
        ResumeContent.Project inherited = put(resumeId, bare, null).content().projects().get(0);
        assertThat(inherited.experienceId()).isEqualTo(id);
        assertThat(inherited.experienceRevision()).isOne();

        // 클라이언트가 개정을 바꾸면 400, 새 연결에 없는 개정이면 404.
        ResumeContent bumped = mapper.convertValue(Map.of("projects", List.of(Map.of("name", "정산 배치",
                "experienceId", id.toString(), "experienceRevision", 2))), ResumeContent.class);
        assertThat(code(catchThrowable(() -> put(resumeId, bumped, null)))).isEqualTo("EXPERIENCE_REVISION_MISMATCH");
        UUID other = resume("v2", Map.of());
        ResumeContent zero = mapper.convertValue(Map.of("projects", List.of(Map.of("name", "정산 배치",
                "experienceId", id.toString(), "experienceRevision", 0))), ResumeContent.class);
        assertThat(code(catchThrowable(() -> put(other, zero, null)))).isEqualTo("EXPERIENCE_REVISION_NOT_FOUND");
        ResumeContent future = mapper.convertValue(Map.of("projects", List.of(Map.of("name", "정산 배치",
                "experienceId", id.toString(), "experienceRevision", 9))), ResumeContent.class);
        assertThat(code(catchThrowable(() -> put(other, future, null)))).isEqualTo("EXPERIENCE_REVISION_MISMATCH");

        // "원본으로 갱신"만 개정을 올린다. 재검토 표시가 사라진다.
        ResumeResponse refreshed = put(resumeId, read(resumeId).content(), List.of(id));
        assertThat(refreshed.content().projects().get(0).experienceRevision()).isEqualTo(2);
        assertThat(detail(id).rows()).singleElement().satisfies(ref -> assertThat(ref.needsReview()).isFalse());
        assertThat(writer.mismatches(owner)).isZero();
    }

    /** 경험 검사: 남의 경험 404, 종류·섹션이 다르면 400, 한 이력서에 같은 경험 두 행 400. 웹 저장에서 사라진 앵커는 unlinkedWarnings. */
    @Test
    void linkerRejectsForeignWrongSectionAndDuplicateExperiences() {
        UUID stranger = newUser();
        owner = stranger;
        asWeb();
        UUID foreign = create(ExperienceKind.PROJECT, "남의 경험", null, null, null).id();
        signIn();
        UUID mine = create(ExperienceKind.PROJECT, "내 경험", null, null, null).id();
        UUID resumeId = resume("v1", Map.of("projects", List.of(Map.of("name", "내 경험", "experienceId", mine.toString()))));

        Throwable notMine = catchThrowable(() -> put(resumeId, mapper.convertValue(Map.of("projects", List.of(
                Map.of("name", "x", "experienceId", foreign.toString()))), ResumeContent.class), null));
        assertThat(notMine).isInstanceOf(ResourceNotFoundException.class);
        assertThat(code(catchThrowable(() -> put(resumeId, mapper.convertValue(Map.of("activities", List.of(
                Map.of("name", "x", "experienceId", mine.toString()))), ResumeContent.class), null))))
                .isEqualTo("EXPERIENCE_SECTION_MISMATCH");
        assertThat(code(catchThrowable(() -> put(resumeId, mapper.convertValue(Map.of("projects", List.of(
                Map.of("name", "a", "experienceId", mine.toString()), Map.of("name", "b", "experienceId", mine.toString()))),
                ResumeContent.class), null)))).isEqualTo("EXPERIENCE_ROW_DUPLICATE");
        // 둘째 행이 다른 개정을 실어 와도 중복이 먼저다(전에는 EXPERIENCE_REVISION_MISMATCH 로 원인이 가려졌다).
        assertThat(code(catchThrowable(() -> put(resumeId, mapper.convertValue(Map.of("projects", List.of(
                Map.of("name", "a", "experienceId", mine.toString(), "experienceRevision", 1),
                Map.of("name", "b", "experienceId", mine.toString(), "experienceRevision", 2))),
                ResumeContent.class), null)))).isEqualTo("EXPERIENCE_ROW_DUPLICATE");

        ResumeResponse cleared = put(resumeId, mapper.convertValue(Map.of("projects", List.of()), ResumeContent.class), null);
        assertThat(cleared.unlinkedWarnings()).containsExactly(mine);
        assertThat(links(resumeId)).isEmpty();
    }

    // ── 경험 서비스 ─────────────────────────────────────────────────────────

    /**
     * 6.5 개정 판정: 같은 facts 재저장은 NONE(개정 없음), 새 id 만 늘면 ADD, 근거만 바뀌어도 ADD, 기존 id 의 값이 바뀌면 CORRECT.
     * ADD 뒤에는 '재검토 필요'가 붙지 않고 CORRECT 뒤에만 붙는다(이력서·자소서 모두). facts 에 expectedVersion 없으면 400, 다르면 409.
     */
    @Test
    void revisionKindsAndReviewFlags() {
        signIn();
        ExperienceFacts first = facts("백엔드", List.of(item("a1", "배치 분할")), List.of(), List.of(note("ev1", "회의록 발췌")));
        ExperienceViews.Saved saved = create(ExperienceKind.PROJECT, "정산 배치", null, first, null);
        UUID id = saved.id();
        resume("v1", Map.of("projects", List.of(Map.of("name", "정산 배치", "experienceId", id.toString()))));
        SelfIntroductionResponse intro = selfIntros.create(new SelfIntroductionCreateRequest(null, null, null, "문제 해결", "답",
                List.of(new ExperienceRef(id, 1))));
        assertThat(intro.experiences()).singleElement().satisfies(link -> assertThat(link.experienceRevision()).isOne());

        assertThat(code(catchThrowable(() -> bank.save(id, new ExperienceRequests.Save(null, null, null, null, first, null,
                null, null, null))))).isEqualTo("VERSION_REQUIRED");
        assertThat(code(catchThrowable(() -> saveFacts(id, saved.version() + 5, first)))).isEqualTo("EXPERIENCE_STALE");

        ExperienceViews.Saved same = saveFacts(id, saved.version(), first);
        assertThat(same.changeKind()).isEqualTo("NONE");
        assertThat(same.revision()).isOne();
        // 표현(앞뒤 공백) 차이는 정규화 뒤 같은 facts 라 개정이 없다. 소재집만 보내도 expectedVersion 은 필수다.
        ExperienceViews.Saved spaced = saveFacts(id, same.version(), facts(" 백엔드 ",
                List.of(item("a1", "  배치 분할 ")), List.of(), List.of(note("ev1", " 회의록 발췌"))));
        assertThat(spaced.changeKind()).isEqualTo("NONE");
        assertThat(spaced.revision()).isOne();
        assertThat(code(catchThrowable(() -> bank.save(id, new ExperienceRequests.Save(null, null, null, null, null,
                new ExperienceDossier("서술", null, null, null), null, null, null))))).isEqualTo("VERSION_REQUIRED");

        ExperienceViews.Saved added = saveFacts(id, spaced.version(), facts("백엔드",
                List.of(item("a1", "배치 분할"), item(null, "모니터링 추가")), List.of(), List.of(note("ev1", "회의록 발췌"))));
        assertThat(added.changeKind()).isEqualTo("ADD");
        ExperienceViews.Saved evidenceOnly = saveFacts(id, added.version(), facts("백엔드",
                List.of(item("a1", "배치 분할"), item("a2", "모니터링 추가")), List.of(),
                List.of(note("ev1", "회의록 발췌"), note(null, "PR 설명 발췌"))));
        assertThat(evidenceOnly.changeKind()).isEqualTo("ADD");
        assertThat(evidenceOnly.revision()).isEqualTo(3);
        ExperienceViews.Detail afterAdds = detail(id);
        assertThat(afterAdds.rows()).allSatisfy(ref -> assertThat(ref.needsReview()).isFalse());
        assertThat(afterAdds.selfIntros()).allSatisfy(ref -> assertThat(ref.needsReview()).isFalse());
        assertThat(afterAdds.facts().actions()).extracting(ExperienceFacts.Item::id).containsExactly("a1", "a2");

        ExperienceViews.Saved corrected = saveFacts(id, evidenceOnly.version(), facts("백엔드",
                List.of(item("a1", "배치 3분할"), item("a2", "모니터링 추가")), List.of(),
                afterAdds.facts().evidence()));
        assertThat(corrected.changeKind()).isEqualTo("CORRECT");
        assertThat(corrected.needsReview()).extracting(ExperienceViews.NeedsReview::type)
                .containsExactlyInAnyOrder("RESUME", "SELF_INTRO");
        ExperienceViews.Detail afterCorrect = detail(id);
        assertThat(afterCorrect.rows()).allSatisfy(ref -> assertThat(ref.needsReview()).isTrue());
        assertThat(afterCorrect.selfIntros()).allSatisfy(ref -> assertThat(ref.needsReview()).isTrue());
        assertThat(afterCorrect.revisions()).extracting(ExperienceViews.RevisionView::changeKind)
                .containsExactly("INIT", "ADD", "ADD", "CORRECT");
    }

    /** 로컬 경로만 있는 근거, 인용 없는 근거, 없는 근거를 가리키는 주장은 400 이다(6.5). */
    @Test
    void evidenceMustBeReadableElsewhere() {
        signIn();
        ExperienceFacts.Evidence local = new ExperienceFacts.Evidence(null, ExperienceFacts.SourceType.URL,
                new ExperienceFacts.Ref(null, null, null, null, null, "C:/Users/me/notes.md"), "발췌", null, null, null, null);
        assertThat(code(catchThrowable(() -> create(ExperienceKind.PROJECT, "x", null,
                facts(null, List.of(), List.of(), List.of(local)), null)))).isEqualTo("EVIDENCE_LOCAL_PATH");
        assertThat(code(catchThrowable(() -> create(ExperienceKind.PROJECT, "x", null,
                facts(null, List.of(new ExperienceFacts.Item(null, "주장", List.of("nope"))), List.of(), List.of()), null))))
                .isEqualTo("UNKNOWN_EVIDENCE");
        // MCP 경로에서는 인용 누락을 Bean Validation 이 거부한다.
        McpPrincipal principal = asMcp();
        assertThatThrownBy(() -> tools.call("experience_save", Map.of("request", Map.of("kind", "PROJECT", "title", "x",
                "facts", Map.of("evidence", List.of(Map.of("sourceType", "NOTE"))))), principal))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quote");
    }

    /**
     * 6.4 삭제: 이력서가 쓰면 409(이력서 이름), 자소서 근거는 detach 없이 409·detach 면 끊고 지운다. 잠금을 거치지 않은 경로가
     * 사용 중 확인 뒤에 연결해도 지연 FK 가 막는다 — 커밋 시점 위반도 409 다.
     */
    @Test
    void deleteChecksUsageAndDeferredForeignKeysGuardTheRest() {
        signIn();
        UUID used = create(ExperienceKind.PROJECT, "쓰는 경험", null, null, null).id();
        UUID resumeId = resume("백엔드 A", Map.of("projects", List.of(Map.of("name", "쓰는 경험", "experienceId", used.toString()))));
        Throwable inResume = catchThrowable(() -> bank.delete(used, true));
        assertThat(code(inResume)).isEqualTo("EXPERIENCE_IN_USE");
        assertThat(inResume).hasMessageContaining("백엔드 A");

        UUID cited = create(ExperienceKind.PROJECT, "근거 경험", null, null, null).id();
        selfIntros.create(new SelfIntroductionCreateRequest(List.of(resumeId), null, null, "협업", "답",
                List.of(new ExperienceRef(cited, 1))));
        assertThat(code(catchThrowable(() -> bank.delete(cited, false)))).isEqualTo("EXPERIENCE_IN_USE");
        assertThat(bank.delete(cited, true).detachedSelfIntros()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from experiences where id = ?", Integer.class, cited)).isZero();

        // 잠금 밖 경로(직접 SQL)가 연결한 경험을 지우려 하면 서비스의 사전 확인이 409 를 준다.
        UUID raced = create(ExperienceKind.PROJECT, "경합 경험", null, null, null).id();
        jdbc.update("insert into resume_experiences (owner_id, resume_id, experience_id, based_on_experience_id, based_on_revision) "
                + "values (?, ?, ?, ?, 1)", owner, resumeId, raced, raced);
        assertThat(code(catchThrowable(() -> bank.delete(raced, false)))).isEqualTo("EXPERIENCE_IN_USE");

        // 확인 뒤에 경험이 지워진 경우: 연결 INSERT 가 커밋 전 IMMEDIATE 에서 실패한다 → 409 EXPERIENCE_CHANGED.
        UUID gone = UUID.randomUUID();
        Throwable changed = catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("insert into resume_experiences (owner_id, resume_id, experience_id, based_on_experience_id, "
                    + "based_on_revision) values (?, ?, ?, ?, 1)", owner, resumeId, gone, gone);
            jdbc.execute("set constraints all immediate");
        }));
        assertThat(code(changed)).isEqualTo("EXPERIENCE_CHANGED");
    }

    /**
     * 6.4 경합: 사용 중 확인을 지난 뒤에 다른 트랜잭션이 연결을 커밋한 경우(확인 질의가 빈 결과를 본 상황을 spy 로 만든다).
     * 삭제는 커밋이 아니라 delete 안의 IMMEDIATE 문에서 409 EXPERIENCE_IN_USE 로 막혀야 한다 — 그 문장을 빼면 이 테스트가 깨진다.
     */
    @Test
    void deleteIsRejectedWhenALinkAppearsAfterTheUsageCheck() {
        signIn();
        UUID raced = create(ExperienceKind.PROJECT, "경합 경험", null, null, null).id();
        UUID resumeId = resume("경합", Map.of());
        jdbc.update("insert into resume_experiences (owner_id, resume_id, experience_id, based_on_experience_id, based_on_revision) "
                + "values (?, ?, ?, ?, 1)", owner, resumeId, raced, raced);
        doReturn(List.of()).when(jdbc).queryForList(startsWith("select distinct r.name"), eq(String.class), any(), any());

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Throwable thrown = catchThrowable(() -> bank.delete(raced, false));
            assertThat(thrown).as("커밋 전, delete 호출 안에서 막힌다").isNotNull();
            assertThat(code(thrown)).isEqualTo("EXPERIENCE_IN_USE");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from experiences where id = ?", Integer.class, raced)).isOne();
    }

    /**
     * REST 진입점(컨트롤러·detach 파라미터·GlobalExceptionHandler)으로 삭제: 사용 중 409, detach=true 면 200, 남의 경험 404.
     * 같은 EXPERIENCE_IN_USE 를 MCP 도구(experience_save 의 종류 변경)와 REST PUT 이 같은 상태·코드로 낸다.
     */
    @Test
    void restDeleteAndMcpShareErrorCodes() throws Exception {
        owner = newUser();
        asWeb();
        UUID foreign = create(ExperienceKind.PROJECT, "남의 경험", null, null, null).id();
        signIn();
        MockMvc rest = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        String path = ApiPaths.EXPERIENCES + "/{id}";
        UUID used = create(ExperienceKind.PROJECT, "쓰는 경험", null, null, null).id();
        resume("백엔드 A", Map.of("projects", List.of(Map.of("name", "쓰는 경험", "experienceId", used.toString()))));
        UUID cited = create(ExperienceKind.PROJECT, "근거 경험", null, null, null).id();
        selfIntros.create(new SelfIntroductionCreateRequest(null, null, null, "협업", "답", List.of(new ExperienceRef(cited, 1))));

        rest.perform(delete(path, used).param("detach", "true"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPERIENCE_IN_USE"));
        rest.perform(delete(path, cited))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPERIENCE_IN_USE"));
        rest.perform(delete(path, cited).param("detach", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.detachedSelfIntros").value(1));
        rest.perform(delete(path, foreign)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

        rest.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path, used)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"ACTIVITY\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPERIENCE_IN_USE"));
        McpPrincipal principal = asMcp();
        Throwable mcp = catchThrowable(() -> tools.call("experience_save", Map.of("id", used.toString(),
                "request", Map.of("kind", "ACTIVITY")), principal));
        assertThat(ApiErrors.translate(mcp).status()).isEqualTo(409);
        assertThat(code(mcp)).isEqualTo("EXPERIENCE_IN_USE");
    }

    /**
     * 2.4 복합 FK: 링커를 거치지 않는 경로(직접 SQL, JSON 앵커로 rebuild)로 남의 경험을 이어도 DB 가 23503 으로 거부한다.
     * 단일 컬럼 FK 로 바꾸면 이 테스트가 깨진다.
     */
    @Test
    void compositeForeignKeysRejectAnotherOwnersExperience() {
        owner = newUser();
        asWeb();
        UUID foreign = create(ExperienceKind.PROJECT, "남의 경험", null, null, null).id();
        signIn();
        UUID resumeId = resume("v1", Map.of());
        UUID intro = selfIntros.create(new SelfIntroductionCreateRequest(null, null, null, "q", "a", List.of())).id();
        TransactionTemplate tx = new TransactionTemplate(transactions);

        Throwable resumeLink = catchThrowable(() -> tx.executeWithoutResult(status -> {
            jdbc.update("insert into resume_experiences (owner_id, resume_id, experience_id, based_on_experience_id, "
                    + "based_on_revision) values (?, ?, ?, ?, 1)", owner, resumeId, foreign, foreign);
            jdbc.execute("set constraints all immediate");
        }));
        assertThat(sqlState(resumeLink)).isEqualTo("23503");
        Throwable introLink = catchThrowable(() -> tx.executeWithoutResult(status -> {
            jdbc.update("insert into self_introduction_experiences (owner_id, self_introduction_id, experience_id, "
                    + "experience_revision, source) values (?, ?, ?, 1, 'USER')", owner, intro, foreign);
            jdbc.execute("set constraints all immediate");
        }));
        assertThat(sqlState(introLink)).isEqualTo("23503");

        jdbc.update("update resumes set content = ?::jsonb where id = ?", "{\"projects\":[{\"name\":\"x\",\"experienceId\":\""
                + foreign + "\",\"experienceRevision\":1}]}", resumeId);
        Throwable rebuilt = catchThrowable(() -> tx.executeWithoutResult(status -> {
            ownerLock.acquire(owner);
            writer.rebuild(owner);
        }));
        assertThat(sqlState(rebuilt)).isEqualTo("23503");
        assertThat(links(resumeId)).isEmpty();
    }

    /** 웹 저장이 검토 확정을 겸하는 것은 facts 를 보낸 저장뿐이다. 메모·skills 만 고친 저장은 AI 기록을 확정하지 않는다. */
    @Test
    void webMetadataSaveDoesNotConfirmAiFacts() {
        signIn();
        McpPrincipal principal = asMcp();
        ExperienceViews.Saved saved = (ExperienceViews.Saved) tools.call("experience_save", Map.of("request", Map.of(
                "kind", "PROJECT", "title", "AI 경험", "facts", Map.of("actions", List.of(Map.of("text", "설계"))))), principal);
        asWeb();
        bank.save(saved.id(), new ExperienceRequests.Save(null, null, null, "메모만", null, null, List.of("Kafka"), null, null));
        ExperienceViews.Detail afterMemo = detail(saved.id());
        assertThat(afterMemo.reviewed()).isFalse();
        assertThat(afterMemo.memo()).isEqualTo("메모만");

        bank.save(saved.id(), new ExperienceRequests.Save(null, null, null, null, afterMemo.facts(), null, null, null,
                afterMemo.version()));
        assertThat(detail(saved.id()).reviewed()).isTrue();
    }

    /** 검토 확정은 보고 있던 개정이어야 한다. MCP 저장은 'AI 기록'(reviewed:false), 웹 확정 뒤 reviewed:true. */
    @Test
    void mcpSaveIsAiRecordUntilWebReview() {
        signIn();
        McpPrincipal principal = asMcp();
        ExperienceViews.Saved saved = (ExperienceViews.Saved) tools.call("experience_save", Map.of("request", Map.of(
                "kind", "PROJECT", "title", "MCP 경험", "facts", Map.of("actions", List.of(Map.of("text", "설계"))))), principal);
        asWeb();
        assertThat(detail(saved.id()).reviewed()).isFalse();
        assertThat(views.index(null, null, null, null).counts().aiRecords()).isOne();
        assertThat(code(catchThrowable(() -> bank.review(saved.id(), new ExperienceRequests.Review(2)))))
                .isEqualTo("EXPERIENCE_STALE");
        assertThat(bank.review(saved.id(), new ExperienceRequests.Review(1)).reviewed()).isTrue();

        // MCP 수정에 facts 를 보내면서 expectedVersion 이 없으면 400 — REST 와 같은 코드다(공용 변환).
        asMcp();
        Throwable mcp = catchThrowable(() -> tools.call("experience_save", Map.of("id", saved.id().toString(), "request",
                Map.of("facts", Map.of("role", "x"))), principal));
        assertThat(code(mcp)).isEqualTo("VERSION_REQUIRED");
    }

    // ── 동시성 (T9) ─────────────────────────────────────────────────────────

    /**
     * 같은 계정의 경험 관계 쓰기는 권고 잠금으로 줄을 서고, lock_timeout(3s)을 넘기면 409 BUSY 다. 다른 계정은 기다리지 않는다.
     */
    @Test
    void sameOwnerWritesQueueAndTimeOutOtherOwnersDoNot() throws Exception {
        UUID other = newUser();
        signIn();
        UUID mine = owner;
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        Future<?> holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ownerLock.acquire(mine);
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        held.await(5, TimeUnit.SECONDS);

        Future<Throwable> blocked = pool.submit(() -> {
            authenticate(new AppSessionUser(mine, "x@example.com", UserRole.USER));
            return catchThrowable(() -> create(ExperienceKind.PROJECT, "대기", null, null, null));
        });
        // 잠금은 10초까지 쥐고 있으므로, 다른 계정이 기다렸다면 3초 뒤 BUSY 로 실패한다. 성공 자체가 "기다리지 않음"이다.
        Future<ExperienceViews.Saved> free = pool.submit(() -> {
            authenticate(new AppSessionUser(other, "y@example.com", UserRole.USER));
            return create(ExperienceKind.PROJECT, "다른 계정", null, null, null);
        });
        assertThat(free.get(10, TimeUnit.SECONDS).id()).as("다른 계정은 기다리지 않는다").isNotNull();
        Throwable timedOut = blocked.get(15, TimeUnit.SECONDS);
        assertThat(code(timedOut)).isEqualTo("BUSY");

        // 줄 서기: 잠금이 3초 안에 풀리면 기다리던 같은 계정 쓰기가 성공한다.
        CountDownLatch queuedStarted = new CountDownLatch(1);
        Future<ExperienceViews.Saved> queued = pool.submit(() -> {
            authenticate(new AppSessionUser(mine, "x@example.com", UserRole.USER));
            queuedStarted.countDown();
            return create(ExperienceKind.PROJECT, "줄 선 쓰기", null, null, null);
        });
        queuedStarted.await(5, TimeUnit.SECONDS);
        Thread.sleep(500);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(queued.get(10, TimeUnit.SECONDS).id()).isNotNull();
        pool.shutdown();
    }

    // ── MCP 도구 ────────────────────────────────────────────────────────────

    /**
     * resume_add_experiences(FACTS): 경험마다 행을 만들고 지금 개정을 쓴다. 이미 있는 경험은 건너뛴다. 행 도구는 experienceId 로
     * 지목해 다듬고 앵커는 그대로다. resume_update 가 연결 행을 빠뜨리면 409 LINKED_ROWS_MISSING, removedExperienceIds 면 통과.
     * missing 에 이 이력서에 없는 은행 경험이 실린다.
     */
    @Test
    @SuppressWarnings("unchecked")
    void mcpResumeToolsUseExperienceAnchors() {
        signIn();
        UUID project = create(ExperienceKind.PROJECT, "정산 배치", "2025.03", facts("백엔드 개발",
                List.of(item(null, "배치 분할")), List.of(item(null, "40분 → 6분")), List.of()), List.of("Kafka", "Java")).id();
        UUID career = create(ExperienceKind.CAREER, "A사", "2023.01", facts(null, List.of(item(null, "결제 API")),
                List.of(), List.of()), null).id();
        UUID resumeId = resume("v1", Map.of());
        McpPrincipal principal = asMcp();

        Map<String, Object> created = (Map<String, Object>) tools.call("resume_get", Map.of("id", resumeId.toString()), principal);
        assertThat((List<ExperienceViews.Missing>) created.get("missing")).extracting(ExperienceViews.Missing::experienceId)
                .containsExactlyInAnyOrder(project, career);

        ExperienceViews.Outline outline = (ExperienceViews.Outline) tools.call("resume_add_experiences", Map.of(
                "id", resumeId.toString(), "request", Map.of("items", List.of(Map.of("experienceId", project.toString()),
                        Map.of("experienceId", career.toString()), Map.of("experienceId", project.toString())))), principal);
        assertThat(outline.missing()).isEmpty();
        ResumeContent content = read(resumeId).content();
        assertThat(content.projects()).singleElement().satisfies(row -> {
            assertThat(row.experienceId()).isEqualTo(project);
            assertThat(row.experienceRevision()).isOne();
            assertThat(row.role()).isEqualTo("백엔드 개발");
            assertThat(row.techStack()).isEqualTo("kafka, java");
            assertThat(row.outcome()).contains("40분 → 6분");
        });
        assertThat(content.experiences()).singleElement().satisfies(row -> assertThat(row.company()).isEqualTo("A사"));
        // 다시 넣어도 건너뛴다.
        tools.call("resume_add_experiences", Map.of("id", resumeId.toString(), "request",
                Map.of("items", List.of(Map.of("experienceId", project.toString())))), principal);
        assertThat(read(resumeId).content().projects()).hasSize(1);

        long version = read(resumeId).version();
        tools.call("resume_row_update", Map.of("id", resumeId.toString(), "section", "projects",
                "experienceId", project.toString(), "request", Map.of("description", "B사용 문구"), "expectedVersion", version), principal);
        ResumeContent.Project edited = read(resumeId).content().projects().get(0);
        assertThat(edited.description()).isEqualTo("B사용 문구");
        assertThat(edited.experienceRevision()).isOne();

        ResumeResponse current = read(resumeId);
        Map<String, Object> withoutCareer = Map.of("name", "v1", "expectedVersion", current.version(),
                "content", Map.of("experiences", List.of()));
        Throwable missing = catchThrowable(() -> tools.call("resume_update", Map.of("id", resumeId.toString(),
                "request", withoutCareer), principal));
        assertThat(code(missing)).isEqualTo("LINKED_ROWS_MISSING");
        Map<String, Object> allowed = new java.util.HashMap<>(withoutCareer);
        allowed.put("removedExperienceIds", List.of(career.toString()));
        tools.call("resume_update", Map.of("id", resumeId.toString(), "request", allowed), principal);
        assertThat(read(resumeId).content().experiences()).isEmpty();
        assertThat(writer.mismatches(owner)).isZero();
    }

    /**
     * T6 섹션 상한(ResumeWriter 1단계): 51번째 행은 PUT 이든 resume_add_experiences 든 400 INVALID_CONTENT 이고,
     * 저장·연결이 하나도 바뀌지 않는다.
     */
    @Test
    void sectionLimitIsCheckedBeforeAnythingIsWritten() {
        signIn();
        UUID experience = create(ExperienceKind.PROJECT, "쉰한 번째", null, null, null).id();
        List<Map<String, Object>> fifty = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) fifty.add(Map.of("name", "행 " + i));
        UUID resumeId = resume("꽉 찬 이력서", Map.of("projects", fifty));
        ResumeResponse before = read(resumeId);

        List<Map<String, Object>> fiftyOne = new java.util.ArrayList<>(fifty);
        fiftyOne.add(Map.of("name", "쉰한 번째", "experienceId", experience.toString()));
        assertThat(code(catchThrowable(() -> put(resumeId, mapper.convertValue(Map.of("projects", fiftyOne),
                ResumeContent.class), null)))).isEqualTo("INVALID_CONTENT");
        assertThat(code(catchThrowable(() -> resumes.addExperiences(resumeId,
                List.of(new ExperienceRequests.AddItem(experience)), null)))).isEqualTo("INVALID_CONTENT");

        ResumeResponse after = read(resumeId);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.content().projects()).hasSize(50);
        assertThat(links(resumeId)).isEmpty();
    }

    /**
     * resume_add_experiences 의 FACTS 행은 이력서 칸 상한(role 500, 설명·성과 5000)에 맞춰 자르고 생략 표시를 붙인다.
     * 사실 상한(항목 2000자×50, role 2000자)은 칸 상한보다 커서 그대로 넣으면 문서 검증이 400 으로 막는다.
     */
    @Test
    void factsRowsFitResumeFieldLimits() {
        signIn();
        List<ExperienceFacts.Item> longActions = List.of(item(null, "가".repeat(2000)), item(null, "나".repeat(2000)),
                item(null, "다".repeat(2000)));
        UUID project = create(ExperienceKind.PROJECT, "긴 경험", null,
                facts("역".repeat(1500), longActions, longActions, List.of()), null).id();
        UUID career = create(ExperienceKind.CAREER, "긴 경력", null,
                facts("역".repeat(1500), longActions, List.of(), List.of()), null).id();
        UUID resumeId = resume("v1", Map.of());

        resumes.addExperiences(resumeId, List.of(new ExperienceRequests.AddItem(project), new ExperienceRequests.AddItem(career)),
                null);
        ResumeContent content = read(resumeId).content();
        assertThat(content.projects()).singleElement().satisfies(row -> {
            assertThat(row.experienceId()).isEqualTo(project);
            assertThat(row.role()).hasSize(500).endsWith("…");
            assertThat(row.description()).hasSize(5000).endsWith("…");
            assertThat(row.outcome()).hasSize(5000).startsWith("- 가");
        });
        assertThat(content.experiences()).singleElement()
                .satisfies(row -> assertThat(row.description()).hasSize(5000).endsWith("…"));
    }

    /**
     * 6.8 자소서 작성 근거: 받은 (경험, 개정) 쌍을 그대로 저장·조회한다. 키가 빠지면 400, 없는 개정은 404, 남의 경험은 404.
     * MCP 수정은 더하기만(기존 연결 유지, 이미 연결된 경험은 건너뜀), 웹은 목록으로 맞춘다.
     */
    @Test
    void selfIntroExperiencePairsRoundTripAndMcpOnlyAdds() {
        UUID stranger = newUser();
        owner = stranger;
        asWeb();
        UUID foreign = create(ExperienceKind.PROJECT, "남의 경험", null, null, null).id();
        signIn();
        UUID a = create(ExperienceKind.PROJECT, "A", null, null, null).id();
        UUID b = create(ExperienceKind.PROJECT, "B", null, null, null).id();
        UUID resumeId = resume("v1", Map.of());
        SelfIntroductionResponse intro = selfIntros.create(new SelfIntroductionCreateRequest(List.of(resumeId), null, null,
                "협업", "답", List.of(new ExperienceRef(a, 1))));

        assertThat(code(catchThrowable(() -> selfIntros.create(new SelfIntroductionCreateRequest(List.of(resumeId), null, null,
                "q", "a", List.of(new ExperienceRef(a, null))))))).isEqualTo("EXPERIENCE_REFERENCE_REQUIRED");
        assertThat(code(catchThrowable(() -> selfIntros.create(new SelfIntroductionCreateRequest(List.of(resumeId), null, null,
                "q", "a", List.of(new ExperienceRef(a, 5))))))).isEqualTo("EXPERIENCE_REVISION_NOT_FOUND");
        assertThat(catchThrowable(() -> selfIntros.create(new SelfIntroductionCreateRequest(List.of(resumeId), null, null,
                "q", "a", List.of(new ExperienceRef(foreign, 1)))))).isInstanceOf(ResourceNotFoundException.class);

        McpPrincipal principal = asMcp();
        tools.call("self_intro_update", Map.of("id", intro.id().toString(), "request", Map.of("resumeIds", List.of(resumeId.toString()),
                "question", "협업", "answer", "답", "experiences", List.of(Map.of("experienceId", b.toString(), "experienceRevision", 1)))),
                principal);
        SelfIntroductionList.Item item = (SelfIntroductionList.Item) tools.call("self_intro_get",
                Map.of("id", intro.id().toString()), principal);
        assertThat(item.experiences()).extracting(SelfIntroductionList.ExperienceUse::experienceId).containsExactlyInAnyOrder(a, b);
        assertThat(item.experiences()).filteredOn(use -> use.experienceId().equals(a))
                .singleElement().satisfies(use -> assertThat(use.trust()).isEqualTo("확정"));
        assertThat(item.experiences()).filteredOn(use -> use.experienceId().equals(b))
                .singleElement().satisfies(use -> {
                    assertThat(use.trust()).isEqualTo("AI 기록");
                    assertThat(use.title()).isEqualTo("B");
                });

        asWeb();
        long version = selfIntros.findById(intro.id()).version();
        SelfIntroductionResponse web = selfIntros.update(intro.id(), new SelfIntroductionUpdateRequest(List.of(resumeId), null, null,
                "협업", "답", version, List.of(new ExperienceRef(b, 1))));
        assertThat(web.experiences()).singleElement().satisfies(link -> {
            assertThat(link.experienceId()).isEqualTo(b);
            assertThat(link.source().name()).isEqualTo("AI");
        });
    }

    /** experience_search 의 자료원이 경험 원본이다: 경험은 EXPERIENCE(skills 정확 일치), 미연결 행만 RESUME_ROW. 형식은 그대로다. */
    @Test
    void searchUsesExperienceBankAndUnlinkedRows() {
        signIn();
        UUID experience = create(ExperienceKind.PROJECT, "정산 배치", null,
                facts("백엔드", List.of(item(null, "Kafka 소비자 분할")), List.of(), List.of()), List.of("Kafka")).id();
        resume("v1", Map.of("projects", List.of(Map.of("name", "정산 배치", "experienceId", experience.toString()),
                Map.of("name", "사내 메시지 큐", "techStack", "Kafka"))));
        ExperienceSearchResult result = search.search(new ExperienceSearchRequest(null, new ExperienceSearchRequest.Posting(
                "B사", null, "서버", null, null, "Kafka 경험", null, List.of()),
                List.of(new ExperienceSearchRequest.Requirement("Kafka", ExperienceSearchRequest.Field.requiredSkills,
                        ExperienceSearchRequest.Kind.SKILL, "Kafka 필수")), null));
        assertThat(result.results()).extracting(ExperienceSearchResult.Result::type)
                .containsExactlyInAnyOrder("EXPERIENCE", "RESUME_ROW");
        ExperienceSearchResult.Result bankResult = result.results().stream().filter(r -> r.type().equals("EXPERIENCE"))
                .findFirst().orElseThrow();
        assertThat(bankResult.experienceId()).isEqualTo(experience);
        assertThat(bankResult.alsoIn()).singleElement().satisfies(source -> assertThat(source.index()).isZero());
        assertThat(result.results().stream().filter(r -> r.type().equals("RESUME_ROW")).findFirst().orElseThrow().title())
                .isEqualTo("사내 메시지 큐");
        assertThat(result.coverageIncomplete()).as("미연결 행이 남아 있다").isTrue();
    }

    /**
     * 6.3 가져오기: 계획은 미연결 묶음이고, CREATE 는 'AI 기록' 개정 1 과 구성 행 앵커, LINK_TO 는 지금 개정으로 잇는다.
     * 행 텍스트는 그대로다. 계획 뒤 이력서가 바뀌면 409. 다시 돌려도 결과가 같다(이미 앵커가 있는 행은 계획에 없다).
     */
    @Test
    void importPlanAndApplyAreIdempotent() {
        signIn();
        UUID existing = create(ExperienceKind.CAREER, "A사", "2023.01", null, null).id();
        resume("v1", Map.of("projects", List.of(Map.of("name", "정산 배치", "startYm", "2025.03", "role", "백엔드",
                "description", "배치 분할", "techStack", "Kafka, k8s")), "experiences", List.of(Map.of("company", "A 사",
                "startYm", "2022.01", "description", "결제"))));
        resume("v2", Map.of("projects", List.of(Map.of("name", "정산배치", "startYm", "2025.3", "role", "리드",
                "description", "배치 분할"))));

        ExperienceViews.ImportPlan plan = imports.plan();
        // 대표 제목은 가장 최근에 고친 이력서의 행이다(묶음 함수). 같은 묶음 키라 두 표기가 한 묶음이다.
        assertThat(plan.groups()).extracting(ExperienceViews.ImportGroup::kind)
                .containsExactly(ExperienceKind.PROJECT, ExperienceKind.CAREER);
        ExperienceViews.ImportGroup batch = plan.groups().get(0);
        assertThat(batch.rows()).hasSize(2);
        ExperienceViews.ImportGroup careerGroup = plan.groups().get(1);

        // 계획 뒤에 이력서가 바뀌면 409.
        Map<UUID, Long> stale = new java.util.HashMap<>(plan.resumeVersions());
        stale.replaceAll((id, version) -> version + 1);
        assertThat(code(catchThrowable(() -> imports.apply(new ExperienceRequests.Import(stale, List.of(
                new ExperienceRequests.ImportDecision(batch.key(), ExperienceRequests.ImportAction.CREATE, null)))))))
                .isEqualTo("IMPORT_PLAN_STALE");

        ExperienceViews.ImportResult result = imports.apply(new ExperienceRequests.Import(plan.resumeVersions(), List.of(
                new ExperienceRequests.ImportDecision(batch.key(), ExperienceRequests.ImportAction.CREATE, null),
                new ExperienceRequests.ImportDecision(careerGroup.key(), ExperienceRequests.ImportAction.LINK_TO, existing))));
        assertThat(result.created()).isOne();
        assertThat(result.linked()).isEqualTo(3);
        UUID createdId = result.experienceIds().get(0);
        ExperienceViews.Detail created = detail(createdId);
        assertThat(created.reviewed()).isFalse();
        assertThat(created.skills()).containsExactly("kafka", "kubernetes");
        assertThat(created.facts().evidence()).hasSize(2)
                .allSatisfy(ev -> assertThat(ev.sourceType()).isEqualTo(ExperienceFacts.SourceType.RESUME_ROW));
        assertThat(created.facts().conflicts()).extracting(ExperienceFacts.Item::text)
                .anySatisfy(text -> assertThat(text).contains("역할"));
        assertThat(created.rows()).hasSize(2).allSatisfy(row -> assertThat(row.experienceRevision()).isOne());
        assertThat(detail(existing).rows()).singleElement().satisfies(row -> assertThat(row.section()).isEqualTo("experiences"));
        assertThat(writer.mismatches(owner)).isZero();

        ExperienceViews.ImportPlan again = imports.plan();
        assertThat(again.groups()).isEmpty();
        assertThat(imports.apply(new ExperienceRequests.Import(again.resumeVersions(), List.of())).created()).isZero();
        assertThat(views.index(null, null, null, null).importHint()).isNull();
    }

    /** 같은 묶음 key 에 결정이 둘이면(CREATE 두 번, CREATE 뒤 LINK_TO) 400 DUPLICATE_DECISION 이고 아무것도 만들지 않는다. */
    @Test
    void importRejectsDuplicateDecisionKeys() {
        signIn();
        UUID existing = create(ExperienceKind.PROJECT, "기존", null, null, null).id();
        resume("v1", Map.of("projects", List.of(Map.of("name", "정산 배치", "startYm", "2025.03"))));
        ExperienceViews.ImportPlan plan = imports.plan();
        String key = plan.groups().get(0).key();

        for (ExperienceRequests.ImportDecision second : List.of(
                new ExperienceRequests.ImportDecision(key, ExperienceRequests.ImportAction.CREATE, null),
                new ExperienceRequests.ImportDecision(key, ExperienceRequests.ImportAction.LINK_TO, existing))) {
            assertThat(code(catchThrowable(() -> imports.apply(new ExperienceRequests.Import(plan.resumeVersions(), List.of(
                    new ExperienceRequests.ImportDecision(key, ExperienceRequests.ImportAction.CREATE, null), second))))))
                    .isEqualTo("DUPLICATE_DECISION");
        }
        assertThat(jdbc.queryForObject("select count(*) from experiences where owner_id = ?", Integer.class, owner)).isOne();
        assertThat(imports.plan().groups()).hasSize(1);
    }

    /**
     * 가져오기 CREATE 초안도 사실 상한(Item.text 2000, period 200)과 정규화를 거친다: 긴 행 설명은 2000자 항목 여러 개로 나뉘어
     * 내용을 잃지 않고, 상세를 읽어 그대로 다시 보내는 저장이 검증을 통과하며 개정을 만들지 않는다(NONE).
     */
    @Test
    void importDraftFitsFactLimitsAndRoundTrips() {
        signIn();
        String description = "가".repeat(4500);
        resume("v1", Map.of("projects", List.of(Map.of("name", "긴 프로젝트", "startYm", "가".repeat(150),
                "endYm", "나".repeat(150), "role", " 백엔드 ", "description", description))));
        ExperienceViews.ImportPlan plan = imports.plan();
        UUID id = imports.apply(new ExperienceRequests.Import(plan.resumeVersions(), List.of(new ExperienceRequests.ImportDecision(
                plan.groups().get(0).key(), ExperienceRequests.ImportAction.CREATE, null)))).experienceIds().get(0);

        ExperienceViews.Detail created = detail(id);
        assertThat(validator.validate(created.facts())).isEmpty();
        assertThat(created.facts().period()).hasSizeLessThanOrEqualTo(200);
        assertThat(created.facts().role()).isEqualTo("백엔드");
        assertThat(created.facts().actions()).hasSize(3)
                .allSatisfy(action -> assertThat(action.evidenceIds()).containsExactly("ev1"));
        assertThat(String.join("", created.facts().actions().stream().map(ExperienceFacts.Item::text).toList()))
                .isEqualTo(description);
        ExperienceViews.Saved again = saveFacts(id, created.version(), created.facts());
        assertThat(again.changeKind()).isEqualTo("NONE");
    }

    /** 목차 전체판: 경험 한 줄 뒤에 미등록 묶음, counts 와 importHint. 남의 id 로 상세를 부르면 404. */
    @Test
    void indexListsBankThenUnlinkedGroups() {
        signIn();
        UUID experience = create(ExperienceKind.PROJECT, "정산 배치", "2025.03", null, List.of("Kafka")).id();
        resume("v1", Map.of("projects", List.of(Map.of("name", "정산 배치", "experienceId", experience.toString()),
                Map.of("name", "미등록 프로젝트"))));
        ExperienceViews.Index index = views.index(null, null, null, null);
        assertThat(index.experiences()).singleElement().satisfies(line -> {
            ExperienceViews.Line value = (ExperienceViews.Line) line;
            assertThat(value.id()).isEqualTo(experience);
            assertThat(value.inResumes()).extracting(ExperienceViews.ResumeRef::name).containsExactly("v1");
            assertThat(value.reviewed()).isTrue();
        });
        assertThat(index.unlinkedRows()).extracting(ExperienceViews.UnlinkedGroup::title).containsExactly("미등록 프로젝트");
        assertThat(index.counts().experiences()).containsEntry(ExperienceKind.PROJECT, 1);
        assertThat(index.importHint()).isNotNull();
        ExperienceViews.Index page = views.index(null, null, null, 1);
        assertThat(page.complete()).isFalse();
        assertThat(page.experiences()).hasSize(1);
        assertThat(views.index(null, null, page.nextCursor(), 1).unlinkedRows()).hasSize(1);
        assertThat(catchThrowable(() -> views.index(null, List.of(UUID.randomUUID()), null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(views.index(null, List.of(experience), null, null).experiences())
                .singleElement().isInstanceOf(ExperienceViews.Detail.class);
    }

    /** 행 도구 experienceId 지목: 없는 경험이면 404 ROW_NOT_FOUND, index 와 experienceId 를 둘 다 주면 400. */
    @Test
    void rowToolTargetsByExperienceId() {
        signIn();
        UUID experience = create(ExperienceKind.PROJECT, "정산 배치", null, null, null).id();
        UUID resumeId = resume("v1", Map.of("projects", List.of(Map.of("name", "앞 행"),
                Map.of("name", "정산 배치", "experienceId", experience.toString()))));
        long version = read(resumeId).version();
        assertThat(resumes.deleteRow(resumeId, com.jobsight.company.resume.ResumeSection.projects, null, experience, version)
                .count()).isOne();
        assertThat(read(resumeId).content().projects()).extracting(ResumeContent.Project::name).containsExactly("앞 행");
        long next = read(resumeId).version();
        assertThat(code(catchThrowable(() -> resumes.deleteRow(resumeId, com.jobsight.company.resume.ResumeSection.projects,
                null, experience, next)))).isEqualTo("ROW_NOT_FOUND");
        assertThat(code(catchThrowable(() -> resumes.deleteRow(resumeId, com.jobsight.company.resume.ResumeSection.projects,
                0, experience, next)))).isEqualTo("TARGET_REQUIRED");
        assertThat(links(resumeId)).isEmpty();
    }

    // ── OCR 리뷰 반영 ───────────────────────────────────────────────────────

    /** 서버가 붙이는 id 는 요청 뒤쪽에 명시된 id 와도 겹치지 않는다. 클라이언트가 겹치게 보내지 않았는데 400 이면 안 된다. */
    @Test
    void generatedFactIdsAvoidExplicitIdsLaterInTheRequest() {
        signIn();
        ExperienceViews.Saved saved = create(ExperienceKind.PROJECT, "정산 배치", null,
                facts("백엔드", List.of(item(null, "첫 행동"), item("a1", "둘째 행동")), List.of(), List.of()), null);

        assertThat(detail(saved.id()).facts().actions()).extracting(ExperienceFacts.Item::id, ExperienceFacts.Item::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a2", "첫 행동"), org.assertj.core.groups.Tuple.tuple("a1", "둘째 행동"));
        assertThat(code(catchThrowable(() -> create(ExperienceKind.PROJECT, "겹침", null,
                facts(null, List.of(item("a1", "하나"), item("a1", "둘")), List.of(), List.of()), null))))
                .isEqualTo("FACT_ID_DUPLICATE");
    }

    /** 두 키가 빠진 행이 저장된 앵커를 이어받을 때도 refreshExperienceIds 를 따른다(지금 개정으로 올린다). */
    @Test
    void inheritedAnchorHonorsRefresh() {
        signIn();
        ExperienceViews.Saved saved = create(ExperienceKind.PROJECT, "정산 배치", "2025.03",
                facts("백엔드", List.of(item("a1", "배치 분할")), List.of(), List.of()), null);
        UUID resumeId = resume("v1", Map.of("projects", List.of(Map.of("name", "정산 배치", "startYm", "2025.03",
                "experienceId", saved.id().toString()))));
        saveFacts(saved.id(), saved.version(), facts("백엔드", List.of(item("a1", "배치 3분할")), List.of(), List.of()));

        ResumeContent bare = mapper.convertValue(Map.of("projects", List.of(Map.of("name", "정산 배치", "startYm", "2025.03"))),
                ResumeContent.class);
        ResumeContent.Project row = put(resumeId, bare, List.of(saved.id())).content().projects().get(0);

        assertThat(row.experienceId()).isEqualTo(saved.id());
        assertThat(row.experienceRevision()).isEqualTo(2);
        assertThat(writer.mismatches(owner)).isZero();
    }

    /** 제목이 빈 행도 앵커가 있으면 사용처다: 상세 rows 에 나오고, 그 경험을 이 이력서의 missing 으로 보고하지 않는다. */
    @Test
    void blankTitleAnchoredRowStillCountsAsUsage() {
        signIn();
        UUID experience = create(ExperienceKind.PROJECT, "정산 배치", null, null, null).id();
        UUID resumeId = resume("v1", Map.of("projects", List.of(Map.of("description", "제목을 지운 행",
                "experienceId", experience.toString()))));

        assertThat(detail(experience).rows()).singleElement().satisfies(row -> assertThat(row.resumeId()).isEqualTo(resumeId));
        assertThat(views.missing(resumeId)).extracting(ExperienceViews.Missing::experienceId).doesNotContain(experience);
        assertThat(views.index(null, null, null, null).importHint()).isNull();
    }

    /**
     * 적용은 이력서를 id 순으로 잠그지만, 대표 행(제목·기간)은 계획과 같이 가장 최근에 고친 이력서의 행이어야 한다.
     * 그렇지 않으면 바뀌지 않은 계획으로도 미리 보기와 다른 제목·기간의 경험이 생긴다.
     */
    @Test
    void importDraftUsesThePlanRepresentativeRow() {
        signIn();
        UUID a = resume("v1", Map.of("trainings", List.of(Map.of("name", "데이터 교육", "startYm", "2024.01", "endYm", "2024.06"))));
        UUID b = resume("v2", Map.of("trainings", List.of(Map.of("name", "데이터교육", "startYm", "2024.01", "endYm", "2024.12"))));
        // id 순으로 앞선 이력서를 더 오래된 것으로 만든다 — 잠금 순서와 대표 순서가 갈린다.
        UUID older = a.compareTo(b) < 0 ? a : b;
        jdbc.update("update resumes set updated_at = now() - interval '1 day' where id = ?", older);
        jdbc.update("update resumes set updated_at = now() where id = ?", older.equals(a) ? b : a);

        ExperienceViews.ImportGroup group = imports.plan().groups().get(0);
        UUID created = imports.apply(new ExperienceRequests.Import(imports.plan().resumeVersions(), List.of(
                new ExperienceRequests.ImportDecision(group.key(), ExperienceRequests.ImportAction.CREATE, null))))
                .experienceIds().get(0);

        ExperienceViews.Detail detail = detail(created);
        assertThat(detail.title()).isEqualTo(group.title());
        assertThat(detail.facts().period()).isEqualTo(group.period());
    }

    /** 가져온 skills 도 저장 검증의 상한(50개·100자)을 지킨다. 넘친 것은 unverified 에 적는다 — 상세를 그대로 다시 저장할 수 있어야 한다. */
    @Test
    void importedSkillsFitSaveLimits() {
        signIn();
        String many = java.util.stream.IntStream.rangeClosed(1, 60).mapToObj(i -> "t" + i).collect(java.util.stream.Collectors.joining(", "));
        resume("v1", Map.of("projects", List.of(Map.of("name", "기술 많은 프로젝트", "techStack", many + ", " + "x".repeat(101)))));
        ExperienceViews.ImportPlan plan = imports.plan();
        UUID id = imports.apply(new ExperienceRequests.Import(plan.resumeVersions(), List.of(new ExperienceRequests.ImportDecision(
                plan.groups().get(0).key(), ExperienceRequests.ImportAction.CREATE, null)))).experienceIds().get(0);

        ExperienceViews.Detail created = detail(id);
        assertThat(created.skills()).hasSizeLessThanOrEqualTo(50).allSatisfy(skill -> assertThat(skill).hasSizeLessThanOrEqualTo(100));
        assertThat(validator.validate(new ExperienceRequests.Save(null, null, null, null, created.facts(), null,
                created.skills(), created.domains(), created.version()))).isEmpty();
        assertThat(created.facts().unverified()).extracting(ExperienceFacts.Item::text).anySatisfy(text -> assertThat(text).contains("기술"));
    }

    /**
     * 한 이력서의 두 행이 같은 묶음이면 같은 경험을 두 행에 걸 수 없다(EXPERIENCE_ROW_DUPLICATE). 계획이 경고를 싣고,
     * 적용은 그 묶음을 링커까지 보내지 않고 무엇을 하면 되는지 담은 400 으로 거절한다. 아무것도 만들지 않는다.
     */
    @Test
    void importGroupWithTwoRowsOfOneResumeIsFlagged() {
        signIn();
        resume("v1", Map.of("projects", List.of(Map.of("name", "정산 배치", "startYm", "2025.03", "description", "1차"),
                Map.of("name", "정산 배치", "startYm", "2025.03", "description", "2차"))));
        ExperienceViews.ImportPlan plan = imports.plan();
        ExperienceViews.ImportGroup group = plan.groups().get(0);
        assertThat(group.warning()).isNotBlank();

        Throwable rejected = catchThrowable(() -> imports.apply(new ExperienceRequests.Import(plan.resumeVersions(), List.of(
                new ExperienceRequests.ImportDecision(group.key(), ExperienceRequests.ImportAction.CREATE, null)))));

        assertThat(code(rejected)).isEqualTo("IMPORT_GROUP_SAME_RESUME");
        assertThat(jdbc.queryForObject("select count(*) from experiences where owner_id = ?", Integer.class, owner)).isZero();
    }

    /** 검색 결과의 기간은 경험 상세·목차와 같이 facts.period 를 우선한다. 시작 연월만 쓰면 끝난 경험이 "2023.01~" 로 나온다. */
    @Test
    void searchResultUsesFactsPeriod() {
        signIn();
        create(ExperienceKind.PROJECT, "정산 배치", "2023.01", new ExperienceFacts("2023.01~2023.06", null, null,
                List.of(item(null, "Kafka 소비자 분할")), null, null, null, null), List.of("Kafka"));
        ExperienceSearchResult result = search.search(new ExperienceSearchRequest(null, new ExperienceSearchRequest.Posting(
                "B사", null, "서버", null, null, "Kafka 경험", null, List.of()), null, null));

        assertThat(result.results()).filteredOn(r -> r.type().equals("EXPERIENCE")).singleElement()
                .satisfies(r -> assertThat(r.period()).isEqualTo("2023.01~2023.06"));
    }
}
