package com.jobsight.company.submission;

import com.jobsight.company.attachment.AttachmentService;
import com.jobsight.company.attachment.dto.AttachmentListItem;
import com.jobsight.company.auth.AppSessionUser;
import com.jobsight.company.common.ApiErrors;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.mcp.McpOAuthConfig;
import com.jobsight.company.mcp.McpPrincipal;
import com.jobsight.company.mcp.McpTools;
import com.jobsight.company.posting.ApplicationStatus;
import com.jobsight.company.posting.EmploymentType;
import com.jobsight.company.posting.JobPostingService;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.posting.dto.JobPostingResponse;
import com.jobsight.company.posting.dto.PositionNameRequest;
import com.jobsight.company.resume.ResumeContent;
import com.jobsight.company.resume.ResumeService;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.selfintro.SelfIntroductionAgentService;
import com.jobsight.company.selfintro.SelfIntroductionService;
import com.jobsight.company.selfintro.dto.SelfIntroductionCreateRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionList;
import com.jobsight.company.submission.dto.SubmissionRequest;
import com.jobsight.company.submission.dto.SubmissionResponse;
import com.jobsight.company.user.UserRole;
import com.jobsight.company.writingguide.WritingGuideService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 제출·작성 기준 트랙(설계 6.6·6.7, 7절 트랙 행) — 실제 PostgreSQL 로 본다. 요청 주체는 SecurityContext 의 실제 주체(웹 세션·MCP 토큰)다.
 */
@Testcontainers
@SpringBootTest
class SubmissionIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JobPostingService postings;
    @Autowired ResumeService resumes;
    @Autowired SelfIntroductionService selfIntros;
    @Autowired SelfIntroductionAgentService agent;
    @Autowired SubmissionService submissions;
    @Autowired WritingGuideService guides;
    @Autowired AttachmentService attachments;
    @Autowired McpTools tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;

    private UUID owner;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

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

    private McpPrincipal asMcp() {
        McpPrincipal principal = new McpPrincipal(owner, Set.copyOf(McpOAuthConfig.SCOPES));
        authenticate(principal);
        return principal;
    }

    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static JobPostingRequest posting(String company, ApplicationStatus status, List<PositionNameRequest> positions) {
        return new JobPostingRequest(null, company, company + " 신입 공채", null, EmploymentType.FULL_TIME, null, status,
                null, null, positions, List.of());
    }

    private UUID attachment() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into attachments (id, owner_id, filename, content_type, size_bytes, data, created_at) "
                + "values (?, ?, 'a.pdf', 'application/pdf', 1, ?, now())", id, owner, new byte[]{1});
        return id;
    }

    private UUID resume(String name, UUID positionId, UUID portfolioFileId) {
        ResumeContent content = portfolioFileId == null ? null
                : mapper.convertValue(Map.of("basic", Map.of("portfolioFileId", portfolioFileId.toString())), ResumeContent.class);
        return resumes.create(new ResumeRequest(name, content, List.of(positionId), null)).id();
    }

    private UUID writtenFor(UUID positionId, String question) {
        return selfIntros.create(new SelfIntroductionCreateRequest(null, List.of(positionId), null, question, "답변")).id();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where owner_id = ?", Integer.class, owner);
    }

    /** 같은 상태 변경을 두 번 보내도 기록은 하나다. 같은 내용으로 다시 SUBMITTED 가 돼도 같은 기록을 돌려준다(content_hash). */
    @Test
    void sameStatusChangeTwiceKeepsOneRecord() {
        signIn();
        JobPostingResponse created = postings.create(posting("가사", ApplicationStatus.INTERESTED,
                List.of(new PositionNameRequest(null, "백엔드"))));
        assertThat(created.submission()).isNull();
        UUID position = created.positions().get(0).id();
        UUID resumeId = resume("가사용", position, null);
        UUID answer = writtenFor(position, "지원 동기");

        JobPostingResponse first = postings.changeStatus(created.id(), ApplicationStatus.SUBMITTED);
        JobPostingResponse second = postings.changeStatus(created.id(), ApplicationStatus.SUBMITTED);
        postings.changeStatus(created.id(), ApplicationStatus.DRAFTING);
        JobPostingResponse third = postings.changeStatus(created.id(), ApplicationStatus.SUBMITTED);

        assertThat(first.submission().level()).isEqualTo(SubmissionService.Level.LINKED);
        assertThat(second.submission()).isEqualTo(first.submission());
        assertThat(third.submission()).isEqualTo(first.submission());
        assertThat(count("submissions")).isOne();
        SubmissionResponse record = submissions.list(created.id()).get(0);
        assertThat(record.resumes().get(0).get("resumeId").asString()).isEqualTo(resumeId.toString());
        assertThat(record.selfIntros().get(0).get("id").asString()).isEqualTo(answer.toString());
        assertThat(record.receipt()).isNull();
    }

    /** SUBMITTED 로 가는 세 경로(웹 드래그=changeStatus, 공고 수정, 공고 생성)와 MCP posting_set_status 모두 LINKED 를 남긴다. */
    @Test
    void everyPathToSubmittedRecordsLinked() {
        signIn();
        // 뒤늦게 등록한 지원 건: 생성할 때 이미 SUBMITTED — 연결 자료가 없어도 LINKED 로만 남는다(제출본이 아니다).
        JobPostingResponse late = postings.create(posting("나사", ApplicationStatus.SUBMITTED, List.of()));
        assertThat(late.submission().level()).isEqualTo(SubmissionService.Level.LINKED);
        assertThat(submissions.list(late.id())).singleElement()
                .satisfies(record -> assertThat(record.resumes()).isEmpty());

        JobPostingResponse edited = postings.create(posting("다사", ApplicationStatus.INTERESTED, List.of()));
        JobPostingResponse updated = postings.update(edited.id(), posting("다사", ApplicationStatus.SUBMITTED, null));
        assertThat(updated.submission().level()).isEqualTo(SubmissionService.Level.LINKED);

        JobPostingResponse viaMcp = postings.create(posting("라사", ApplicationStatus.DRAFTING, List.of()));
        McpPrincipal principal = asMcp();
        JobPostingResponse response = (JobPostingResponse) tools.call("posting_set_status",
                Map.of("id", viaMcp.id().toString(), "request", Map.of("status", "SUBMITTED")), principal);
        assertThat(response.submission().level()).isEqualTo(SubmissionService.Level.LINKED);
        JobPostingResponse again = (JobPostingResponse) tools.call("posting_set_status",
                Map.of("id", viaMcp.id().toString(), "request", Map.of("status", "SUBMITTED")), principal);
        assertThat(again.submission().id()).isEqualTo(response.submission().id());

        assertThat(count("submissions")).isEqualTo(3);
    }

    /**
     * 사용자 확인 제출본은 고른 문서의 스냅샷이다. 이력서 안 첨부와 접수 근거 첨부를 submission_attachments 로 남기고,
     * 그 첨부의 단독 삭제는 지연 FK 가 커밋에서 막는다(409 ATTACHMENT_IN_USE). 계정 삭제 연쇄는 통과한다.
     */
    @Test
    void confirmedSnapshotPinsAttachmentsAndAccountDeleteCascades() {
        signIn();
        JobPostingResponse created = postings.create(posting("마사", ApplicationStatus.INTERESTED,
                List.of(new PositionNameRequest(null, "데이터"))));
        UUID position = created.positions().get(0).id();
        UUID portfolio = attachment();
        UUID resumeId = resume("마사용", position, portfolio);
        UUID receiptFile = attachment();
        guides.update(new WritingGuideService.UpdateRequest("- 수치는 측정 환경과 함께 쓴다", 0));

        assertThatThrownBy(() -> submissions.confirm(created.id(), new SubmissionRequest(
                SubmissionService.Level.USER_CONFIRMED, List.of(), List.of(), null, null, null)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SUBMISSION_EMPTY"));
        assertThatThrownBy(() -> submissions.confirm(created.id(), new SubmissionRequest(
                SubmissionService.Level.RECEIPT_VERIFIED, List.of(resumeId), List.of(), " ", null, null)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("RECEIPT_REQUIRED"));
        assertThatThrownBy(() -> submissions.confirm(created.id(), new SubmissionRequest(
                SubmissionService.Level.RECEIPT_VERIFIED, List.of(resumeId), List.of(), null, null, List.of(UUID.randomUUID()))))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("ATTACHMENT_NOT_FOUND"));

        SubmissionResponse confirmed = submissions.confirm(created.id(), new SubmissionRequest(
                SubmissionService.Level.USER_CONFIRMED, List.of(resumeId), List.of(), null, null, null));
        SubmissionResponse same = submissions.confirm(created.id(), new SubmissionRequest(
                SubmissionService.Level.USER_CONFIRMED, List.of(resumeId), List.of(), null, null, null));
        SubmissionResponse receipt = submissions.confirm(created.id(), new SubmissionRequest(
                SubmissionService.Level.RECEIPT_VERIFIED, List.of(resumeId), List.of(), "접수가 완료되었습니다", "A-123",
                List.of(receiptFile)));

        assertThat(same.id()).isEqualTo(confirmed.id());
        assertThat(confirmed.attachmentIds()).containsExactly(portfolio);
        assertThat(receipt.attachmentIds()).containsExactlyInAnyOrder(portfolio, receiptFile);
        assertThat(receipt.receipt().get("receiptNumber").asString()).isEqualTo("A-123");
        assertThat(submissions.list(created.id())).extracting(SubmissionResponse::level)
                .containsExactlyInAnyOrder(SubmissionService.Level.USER_CONFIRMED, SubmissionService.Level.RECEIPT_VERIFIED);

        Throwable blocked = catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                attachments.delete(receiptFile)));
        assertThat(ApiErrors.translate(blocked).code()).isEqualTo("ATTACHMENT_IN_USE");
        assertThat(blocked).hasMessageContaining("'마사 신입 공채' 제출 기록");
        assertThat(jdbc.queryForObject("select count(*) from attachments where id = ?", Integer.class, receiptFile)).isOne();
        // 서랍 목록도 같은 판정을 쓴다. 접수 근거 파일은 이력서에 없고 제출 기록만 가리킨다.
        assertThat(attachments.list()).filteredOn(item -> item.id().equals(receiptFile)).singleElement()
                .satisfies(item -> {
                    assertThat(item.usedBy()).extracting(AttachmentListItem.UsedBy::type).containsOnly("SUBMISSION");
                    assertThat(item.usedBy()).extracting(AttachmentListItem.UsedBy::name).containsOnly("마사 신입 공채");
                    assertThat(item.deletable()).isFalse();
                });

        jdbc.update("delete from app_users where id = ?", owner);
        for (String table : List.of("submissions", "submission_attachments", "attachments", "job_postings")) {
            assertThat(count(table)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from writing_guides where owner_id = ?", Integer.class, owner)).isZero();
    }

    /**
     * recorded_at 은 트랜잭션 시작(now())이 아니라 계정 잠금을 얻은 뒤 쓴 시각이다. 잠금을 기다린 트랜잭션의 기록이
     * 먼저 커밋된 기록보다 "오래된" 것으로 정렬되면 최신 확인 제출본(alreadyUsedFor)·최신 LINKED 가 뒤집힌다.
     */
    @Test
    void recordedAtIsWriteTimeNotTransactionStart() {
        signIn();
        JobPostingResponse created = postings.create(posting("시사", ApplicationStatus.INTERESTED,
                List.of(new PositionNameRequest(null, "백엔드"))));
        UUID resumeId = resume("시사용", created.positions().get(0).id(), null);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            java.time.Instant started = jdbc.queryForObject("select now()", java.sql.Timestamp.class).toInstant();
            try {
                Thread.sleep(50); // 잠금 대기를 흉내 낸다
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            SubmissionResponse confirmed = submissions.confirm(created.id(), new SubmissionRequest(
                    SubmissionService.Level.USER_CONFIRMED, List.of(resumeId), List.of(), null, null, null));
            assertThat(confirmed.recordedAt()).isAfterOrEqualTo(started.plusMillis(50));
        });
    }

    /** 남의 공고 기록은 404, 남의 이력서·자소서·근거 파일로 확인할 수 없다. */
    @Test
    void otherOwnersDataIsInvisible() {
        signIn();
        UUID mine = owner;
        JobPostingResponse myPosting = postings.create(posting("바사", ApplicationStatus.INTERESTED,
                List.of(new PositionNameRequest(null, "앱"))));
        UUID myResume = resume("바사용", myPosting.positions().get(0).id(), null);
        UUID myFile = attachment();

        signIn();
        JobPostingResponse theirs = postings.create(posting("사사", ApplicationStatus.INTERESTED, List.of()));
        assertThat(mine).isNotEqualTo(owner);
        assertThatThrownBy(() -> submissions.list(myPosting.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> submissions.confirm(myPosting.id(), new SubmissionRequest(
                SubmissionService.Level.USER_CONFIRMED, List.of(myResume), List.of(), null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> submissions.confirm(theirs.id(), new SubmissionRequest(
                SubmissionService.Level.USER_CONFIRMED, List.of(myResume), List.of(), null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        UUID theirResume = resumes.create(new ResumeRequest("사사용", null, List.of(), null)).id();
        assertThatThrownBy(() -> submissions.confirm(theirs.id(), new SubmissionRequest(
                SubmissionService.Level.RECEIPT_VERIFIED, List.of(theirResume), List.of(), null, null, List.of(myFile))))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    /**
     * alreadyUsedFor(설계 6.6): 사용자 확인 제출본이 있는 공고는 그 스냅샷의 답변만 "낸 것"이다. 없으면 지금 연결로 판단하고 "제출본 미확인".
     */
    @Test
    void alreadyUsedForPrefersConfirmedSnapshot() {
        signIn();
        JobPostingResponse confirmedPosting = postings.create(posting("아사", ApplicationStatus.INTERESTED,
                List.of(new PositionNameRequest(null, "백엔드"))));
        UUID confirmedPosition = confirmedPosting.positions().get(0).id();
        JobPostingResponse openPosting = postings.create(posting("자사", ApplicationStatus.INTERESTED,
                List.of(new PositionNameRequest(null, "플랫폼"))));
        UUID openPosition = openPosting.positions().get(0).id();
        UUID sent = writtenFor(confirmedPosition, "협업 경험");
        UUID notSent = writtenFor(confirmedPosition, "갈등 경험");
        UUID elsewhere = writtenFor(openPosition, "문제 해결");
        // 지금 연결은 없지만 실제로 낸 답변(예: 연결을 나중에 지웠다).
        UUID unlinked = selfIntros.create(new SelfIntroductionCreateRequest(
                List.of(resumes.create(new ResumeRequest("기본", null, List.of(), null)).id()), null, null, "성장 과정", "답변")).id();
        submissions.confirm(confirmedPosting.id(), new SubmissionRequest(SubmissionService.Level.USER_CONFIRMED,
                List.of(), List.of(sent, unlinked), null, null, null));

        assertThat(agent.get(sent).alreadyUsedFor()).singleElement().satisfies(used -> {
            assertThat(used.company()).isEqualTo("아사");
            assertThat(used.submission()).isEqualTo("사용자가 확인한 제출본");
        });
        assertThat(agent.get(notSent).alreadyUsedFor()).isEmpty();
        assertThat(agent.get(elsewhere).alreadyUsedFor()).extracting(SelfIntroductionList.UsedFor::submission)
                .containsExactly("제출본 미확인");
        assertThat(agent.get(unlinked).alreadyUsedFor()).singleElement().satisfies(used -> {
            assertThat(used.company()).isEqualTo("아사");
            assertThat(used.position()).isEqualTo("아사 신입 공채");
            assertThat(used.submission()).isEqualTo("사용자가 확인한 제출본");
        });
    }

    /**
     * 확인 제출본이 있는 공고에서는 "그때 낸 직무"가 사용처다. 제출 뒤 같은 공고의 다른 직무(B)로 연결을 바꿔도
     * 확인 수준은 스냅샷의 직무(A)에 붙는다(OCR 리뷰 — 지금 직접 출처 B 에 붙이고 A 를 숨겼다).
     */
    @Test
    void alreadyUsedForNamesTheSnapshotPositionNotTheCurrentLink() {
        signIn();
        JobPostingResponse created = postings.create(posting("차사", ApplicationStatus.INTERESTED, List.of(
                new PositionNameRequest(null, "백엔드"), new PositionNameRequest(null, "데이터"))));
        UUID backend = created.positions().stream().filter(p -> p.name().equals("백엔드")).findFirst().orElseThrow().id();
        UUID data = created.positions().stream().filter(p -> p.name().equals("데이터")).findFirst().orElseThrow().id();
        UUID answer = writtenFor(backend, "지원 동기");
        submissions.confirm(created.id(), new SubmissionRequest(SubmissionService.Level.USER_CONFIRMED,
                List.of(), List.of(answer), null, null, null));
        selfIntros.update(answer, new com.jobsight.company.selfintro.dto.SelfIntroductionUpdateRequest(List.of(), List.of(data),
                null, "지원 동기", "답변", selfIntros.findById(answer).version()));

        assertThat(agent.get(answer).alreadyUsedFor()).singleElement().satisfies(used -> {
            assertThat(used.position()).isEqualTo("백엔드");
            assertThat(used.submission()).isEqualTo("사용자가 확인한 제출본");
        });
    }

    /** resumeId 필터도 소유자 확인을 한다. 남의 것·없는 이력서는 빈 목록이 아니라 404 다(OCR 리뷰). */
    @Test
    void agentListRejectsUnknownResumeFilter() {
        signIn();
        UUID foreign = resumes.create(new ResumeRequest("남의 이력서", null, List.of(), null)).id();
        signIn();

        assertThatThrownBy(() -> agent.list(null, foreign, null, null, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> agent.list(null, UUID.randomUUID(), null, null, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** 첫 일을 트랜잭션 안에서 하고 커밋을 미룬 채 둘째 일을 시작한다. 둘째가 잠금에서 기다렸는지와 그 결과(예외)를 돌려준다. */
    private record Raced(boolean waited, Throwable failure) {}

    private Raced race(Runnable first, Runnable second) throws Exception {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<?> holder = pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    first.run();
                    held.countDown();
                    try {
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            });
            assertThat(held.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<?> waiter = pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                second.run();
            });
            long deadline = System.currentTimeMillis() + 2000;
            boolean waited = false;
            while (!waiter.isDone() && System.currentTimeMillis() < deadline) {
                if (jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
                        + "and wait_event_type = 'Lock'", Integer.class) > 0) {
                    waited = true;
                    break;
                }
                Thread.onSpinWait();
            }
            release.countDown();
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
            try {
                waiter.get(10, java.util.concurrent.TimeUnit.SECONDS);
                return new Raced(waited, null);
            } catch (java.util.concurrent.ExecutionException e) {
                return new Raced(waited, e.getCause());
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 자소서 삭제도 계정 잠금(T9)을 탄다. 제출 확인이 소유 확인 뒤 스냅샷을 읽는 사이에 삭제가 끼어들면 고른 답변이
     * 스냅샷에서 조용히 빠진다(OCR 리뷰). 확인이 잠금을 쥔 동안 삭제는 기다린다.
     */
    @Test
    void selfIntroDeleteWaitsForSubmissionSnapshot() throws Exception {
        signIn();
        JobPostingResponse created = postings.create(posting("카사", ApplicationStatus.INTERESTED,
                List.of(new PositionNameRequest(null, "백엔드"))));
        UUID answer = writtenFor(created.positions().get(0).id(), "협업 경험");

        Raced raced = race(() -> submissions.confirm(created.id(), new SubmissionRequest(SubmissionService.Level.USER_CONFIRMED,
                List.of(), List.of(answer), null, null, null)), () -> selfIntros.delete(answer));

        assertThat(raced.waited()).as("삭제가 제출 확인의 계정 잠금을 기다린다").isTrue();
        assertThat(raced.failure()).isNull();
        assertThat(submissions.list(created.id())).singleElement()
                .satisfies(record -> assertThat(record.selfIntros()).hasSize(1));
    }

    /**
     * 작성 기준을 처음 두 번 동시에 저장하면 둘째는 없는 행을 잠그지 못한다. 둘째는 일반 CONFLICT 가 아니라
     * WRITING_GUIDE_STALE 이어야 화면이 version 을 다시 읽는다(OCR 리뷰).
     */
    @Test
    void concurrentFirstWritingGuideSaveIsStale() throws Exception {
        signIn();

        Raced raced = race(() -> guides.update(new WritingGuideService.UpdateRequest("먼저", 0)),
                () -> guides.update(new WritingGuideService.UpdateRequest("나중", 0)));

        assertThat(raced.failure()).isInstanceOfSatisfying(ApiRuleException.class,
                e -> assertThat(e.getCode()).isEqualTo("WRITING_GUIDE_STALE"));
        assertThat(guides.get().body()).isEqualTo("먼저");
    }

    /**
     * 작성 기준: 웹에서 version 을 확인하며 고치고, MCP 는 writing_guide_get 으로 읽는다. MCP 로 저장한 자소서는 writing_context 에
     * 작성 기준 version·쓴 경험 개정·검색 context 를 남기고, 웹 저장은 그 값을 바꾸지 않는다.
     */
    @Test
    void writingGuideVersionLandsInWritingContext() {
        signIn();
        assertThat(guides.get().version()).isZero();
        guides.update(new WritingGuideService.UpdateRequest("- 과장하지 않는다", 0));
        assertThatThrownBy(() -> guides.update(new WritingGuideService.UpdateRequest("덮어쓰기", 0)))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("WRITING_GUIDE_STALE"));
        guides.update(new WritingGuideService.UpdateRequest("- 과장하지 않는다\n- 수치는 출처와 함께", 1));
        UUID resumeId = resumes.create(new ResumeRequest("기본", null, List.of(), null)).id();

        McpPrincipal principal = asMcp();
        WritingGuideService.Guide read = (WritingGuideService.Guide) tools.call("writing_guide_get", Map.of(), principal);
        assertThat(read.version()).isEqualTo(2);
        assertThat(read.body()).contains("수치는 출처와 함께");

        var created = (com.jobsight.company.selfintro.dto.SelfIntroductionResponse) tools.call("self_intro_create", Map.of(
                "request", Map.of("resumeIds", List.of(resumeId.toString()), "question", "지원 동기", "answer", "AI 초안",
                        "searchContext", Map.of("dataVersion", "d1", "searchConfig", "s1", "embeddingModel", "m1",
                                "indexGeneration", "g1", "indexBehind", false))), principal);
        JsonNode context = mapper.readTree(created.writingContext());
        assertThat(context.get("writingGuideVersion").asInt()).isEqualTo(2);
        assertThat(context.get("searchContext").get("dataVersion").asString()).isEqualTo("d1");
        assertThat(context.get("experiences")).isEmpty();

        asWeb();
        selfIntros.update(created.id(), new com.jobsight.company.selfintro.dto.SelfIntroductionUpdateRequest(
                List.of(resumeId), null, null, "지원 동기", "사람이 고친 답변", created.version()));
        String stored = jdbc.queryForObject("select writing_context::text from self_introductions where id = ?", String.class, created.id());
        assertThat(mapper.readTree(stored).get("searchContext").get("dataVersion").asString()).isEqualTo("d1");
        assertThat(agent.get(created.id()).writingContext()).isNotNull();
    }
}
