package com.jobsight.company.attachment;

import com.jobsight.company.attachment.dto.AttachmentListItem;
import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiErrors;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.experience.ExperienceBankService;
import com.jobsight.company.experience.ExperienceKind;
import com.jobsight.company.experience.dto.ExperienceFacts;
import com.jobsight.company.experience.dto.ExperienceRequests;
import com.jobsight.company.experience.dto.ExperienceViews;
import com.jobsight.company.posting.ApplicationStatus;
import com.jobsight.company.posting.EmploymentType;
import com.jobsight.company.posting.JobPostingService;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.resume.ResumeContent;
import com.jobsight.company.resume.ResumeService;
import com.jobsight.company.resume.dto.ResumeCopyRequest;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.resume.dto.ResumeResponse;
import com.jobsight.company.submission.SubmissionService;
import com.jobsight.company.submission.dto.SubmissionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;

/**
 * 첨부 삭제(설계 6.9): 네이티브 for update → 사용처 판정 → 409, 규칙 B(for key share, 400), 서랍 목록.
 * 동시성은 실제 PostgreSQL 행 잠금으로 본다 — 트랜잭션이 커밋돼야 경쟁이 생기므로 클래스에 @Transactional 을 걸지 않는다.
 */
@Testcontainers
@SpringBootTest
class AttachmentDeleteIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean CurrentUser currentUser;
    @Autowired AttachmentService attachments;
    @Autowired ResumeService resumes;
    @Autowired ExperienceBankService experiences;
    @Autowired JobPostingService postings;
    @Autowired SubmissionService submissions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void users() {
        given(currentUser.id()).willReturn(OWNER_ID);
        for (UUID id : List.of(OWNER_ID, OTHER_ID)) {
            jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                    + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now()) on conflict (id) do nothing",
                    id, id + "@example.com", "sub-" + id);
        }
    }

    private UUID attachment(UUID owner, String filename, int daysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into attachments (id, owner_id, filename, content_type, size_bytes, data, created_at) "
                + "values (?, ?, ?, 'application/pdf', 5, ?, now() - make_interval(days => ?))",
                id, owner, filename, "%PDF-".getBytes(StandardCharsets.US_ASCII), daysAgo);
        return id;
    }

    private ResumeContent certificate(String fileId) {
        return mapper.convertValue(Map.of("certificates", List.of(Map.of("name", "정보처리기사", "fileId", fileId))),
                ResumeContent.class);
    }

    private ResumeContent certificates(String... fileIds) {
        return mapper.convertValue(Map.of("certificates", Arrays.stream(fileIds)
                .map(fileId -> Map.of("name", "자격증", "fileId", fileId)).toList()), ResumeContent.class);
    }

    private ResumeResponse resume(String name, String fileId) {
        return resumes.create(new ResumeRequest(name, certificate(fileId), List.of(), null));
    }

    private ResumeResponse save(ResumeResponse resume, ResumeContent content) {
        return resumes.update(resume.id(), new ResumeRequest(resume.name(), content, null, resume.version()));
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("select count(*) from attachments where id = ?", Integer.class, id) == 1;
    }

    private static String code(Throwable thrown) {
        return ApiErrors.translate(thrown).code();
    }

    private static ExperienceFacts evidenceOn(UUID... attachmentIds) {
        return new ExperienceFacts(null, null, null, null, null, Arrays.stream(attachmentIds).map(id -> new ExperienceFacts.Evidence(null,
                ExperienceFacts.SourceType.ATTACHMENT, new ExperienceFacts.Ref(null, null, null, null, id, null),
                "수료증 사본", null, null, null, null)).toList(), null, null);
    }

    private static ExperienceFacts noEvidence() {
        return new ExperienceFacts("2025", null, null, null, null, List.of(), null, null);
    }

    private static ExperienceRequests.Save experience(UUID id, ExperienceFacts facts, Long version) {
        return new ExperienceRequests.Save(id == null ? ExperienceKind.TRAINING : null, id == null ? "데이터 교육" : null,
                null, null, facts, null, null, null, version);
    }

    @Test
    void deletesUnusedFileAndHidesOthers() {
        UUID mine = attachment(OWNER_ID, "안 쓰는 파일.pdf", 0);
        UUID theirs = attachment(OTHER_ID, "남의 파일.pdf", 30);

        assertThatThrownBy(() -> attachments.delete(theirs)).isInstanceOf(ResourceNotFoundException.class);
        attachments.delete(mine);

        assertThat(exists(mine)).isFalse();
        assertThat(exists(theirs)).isTrue();
    }

    /** 복제본이 같은 id 를 쓴다. 원본에서 빼고 저장한 뒤 지우려 해도 409 이고 파일이 남는다(6.9 검증 1). */
    @Test
    void fileUsedByACopyIsKept() {
        UUID file = attachment(OWNER_ID, "자격증.pdf", 0);
        ResumeResponse source = resume("원본", file.toString());
        resumes.copy(source.id(), new ResumeCopyRequest("복제본"));
        save(source, certificate(""));

        Throwable blocked = catchThrowable(() -> attachments.delete(file));

        assertThat(code(blocked)).isEqualTo("ATTACHMENT_IN_USE");
        assertThat(blocked).hasMessageContaining("이력서 '복제본'").hasMessageNotContaining("원본");
        assertThat(exists(file)).isTrue();
    }

    /** 규칙 B: 새로 적는 id 가 내 파일이 아니면 400. 이미 저장돼 있던(지워진) id 는 저장을 막지 않는다(6.9 검증 4). */
    @Test
    void ruleBRejectsNewForeignIdsButKeepsStoredOnes() {
        UUID theirs = attachment(OTHER_ID, "남의 파일.pdf", 0);
        UUID mine = attachment(OWNER_ID, "내 파일.pdf", 0);

        assertThat(code(catchThrowable(() -> resume("남의 것", theirs.toString())))).isEqualTo("ATTACHMENT_NOT_FOUND");
        assertThat(code(catchThrowable(() -> resume("없는 것", UUID.randomUUID().toString())))).isEqualTo("ATTACHMENT_NOT_FOUND");
        // 대문자·축약형은 사용처 판정(텍스트 포함)이 못 찾으므로 새로 적을 수 없다.
        assertThat(code(catchThrowable(() -> resume("대문자", mine.toString().toUpperCase())))).isEqualTo("ATTACHMENT_NOT_FOUND");
        assertThat(code(catchThrowable(() -> resume("손글씨", "파일 아님")))).isEqualTo("ATTACHMENT_NOT_FOUND");

        ResumeResponse kept = resume("내 것", mine.toString());
        String stale = UUID.randomUUID().toString();
        jdbc.update("update resumes set content = jsonb_set(content, '{certificates,0,fileId}', to_jsonb(?::text)) where id = ?",
                stale, kept.id());
        ResumeResponse reread = resumes.findById(kept.id());
        UUID added = attachment(OWNER_ID, "새 내 파일.pdf", 0);

        // content 를 명시해 보낸다: 저장돼 있던 지워진 id 와 새 id 가 한 문서에 섞인다. 새 id 만 검사한다.
        assertThat(code(catchThrowable(() -> save(reread, certificates(stale, theirs.toString())))))
                .as("저장된 값의 통과가 새로 적은 남의 id 검사를 가리지 않는다").isEqualTo("ATTACHMENT_NOT_FOUND");
        ResumeResponse saved = save(reread, certificates(stale, added.toString()));
        assertThat(saved.content().certificates()).extracting(row -> row.fileId()).containsExactly(stale, added.toString());
    }

    /** 경험 근거(지금 facts 와 지난 개정)도 사용처다. 근거를 새로 적는 경험 쓰기에도 규칙 B 가 걸린다. */
    @Test
    void experienceEvidenceAndPastRevisionsKeepTheFile() {
        UUID file = attachment(OWNER_ID, "수료증.pdf", 0);
        UUID theirs = attachment(OTHER_ID, "남의 수료증.pdf", 0);

        assertThat(code(catchThrowable(() -> experiences.save(null, experience(null, evidenceOn(theirs), null)))))
                .isEqualTo("ATTACHMENT_NOT_FOUND");
        ExperienceViews.Saved created = experiences.save(null, experience(null, evidenceOn(file), null));
        assertThat(catchThrowable(() -> attachments.delete(file))).hasMessageContaining("경험 '데이터 교육'");

        experiences.save(created.id(), experience(created.id(), noEvidence(), created.version()));
        Throwable blocked = catchThrowable(() -> attachments.delete(file));

        assertThat(code(blocked)).isEqualTo("ATTACHMENT_IN_USE");
        assertThat(blocked).hasMessageContaining("개정 이력");
        assertThat(exists(file)).isTrue();
    }

    /**
     * 지금 근거와 개정이 함께 가리키면(근거를 저장한 직후가 늘 그렇다) 개정 사용처도 따로 보인다. 지금 근거만 보이면
     * "근거를 빼고 저장"을 안내하는데, 그렇게 해도 개정이 남아 지울 수 없다(OCR 리뷰).
     */
    @Test
    void currentEvidenceAlsoShowsRevisionRetention() {
        UUID file = attachment(OWNER_ID, "증빙.pdf", 30);
        ExperienceViews.Saved created = experiences.save(null, experience(null, evidenceOn(file), null));

        Throwable blocked = catchThrowable(() -> attachments.delete(file));

        assertThat(blocked).hasMessageContaining("개정 이력").hasMessageNotContaining("근거를 빼고 저장하면 됩니다");
        assertThat(attachments.list()).filteredOn(item -> item.id().equals(file)).singleElement()
                .satisfies(item -> assertThat(item.usedBy()).containsExactlyInAnyOrder(
                        new AttachmentListItem.UsedBy("EXPERIENCE", created.id(), "데이터 교육"),
                        new AttachmentListItem.UsedBy("EXPERIENCE_REVISION", created.id(), "데이터 교육")));
    }

    /**
     * 규칙 B 이전에 저장된 대문자 UUID 첨부 칸도 사용처다. UUID.fromString 이 받아 주므로 이력서는 그 파일을 계속 보여 주는데,
     * 대소문자를 가리는 판정이면 지워 버린다(OCR 리뷰).
     */
    @Test
    void legacyUppercaseReferenceKeepsTheFile() {
        UUID file = attachment(OWNER_ID, "옛 자격증.pdf", 30);
        ResumeResponse legacy = resume("옛 이력서", file.toString());
        jdbc.update("update resumes set content = jsonb_set(content, '{certificates,0,fileId}', to_jsonb(?::text)) where id = ?",
                file.toString().toUpperCase(), legacy.id());

        Throwable blocked = catchThrowable(() -> attachments.delete(file));

        assertThat(code(blocked)).isEqualTo("ATTACHMENT_IN_USE");
        assertThat(exists(file)).isTrue();
    }

    /** 경험 수정의 규칙 B: 새로 더한 근거만 잡는다. 이미 저장된(지워진) 근거는 통과하고, 남의 새 근거는 400 이다. */
    @Test
    void experienceUpdateLocksOnlyNewEvidence() {
        UUID stale = attachment(OWNER_ID, "지워질 수료증.pdf", 0);
        UUID added = attachment(OWNER_ID, "새 수료증.pdf", 0);
        UUID theirs = attachment(OTHER_ID, "남의 수료증.pdf", 0);
        ExperienceViews.Saved created = experiences.save(null, experience(null, evidenceOn(stale), null));
        // 규칙 B 이전에 지워진 파일을 흉내 낸다(경험 근거에는 FK 가 없다).
        jdbc.update("delete from attachments where id = ?", stale);

        assertThat(code(catchThrowable(() -> experiences.save(created.id(),
                experience(created.id(), evidenceOn(stale, theirs), created.version()))))).isEqualTo("ATTACHMENT_NOT_FOUND");
        ExperienceViews.Saved updated = experiences.save(created.id(),
                experience(created.id(), evidenceOn(stale, added), created.version()));

        assertThat(updated.version()).isGreaterThan(created.version());
        assertThat(code(catchThrowable(() -> attachments.delete(added)))).isEqualTo("ATTACHMENT_IN_USE");
    }

    /** 서랍 목록: 사용처와 서버 계산 deletable(미사용이고 올린 지 7일이 지남). 남의 파일은 없다. */
    @Test
    void drawerListsUsageAndDeletability() {
        UUID used = attachment(OWNER_ID, "쓰는 파일.pdf", 30);
        UUID fresh = attachment(OWNER_ID, "새 파일.pdf", 1);
        UUID old = attachment(OWNER_ID, "오래된 파일.pdf", 8);
        attachment(OTHER_ID, "남의 파일.pdf", 30);
        ResumeResponse resume = resume("서랍 이력서", used.toString());

        Map<UUID, AttachmentListItem> items = new java.util.HashMap<>();
        attachments.list().forEach(item -> items.put(item.id(), item));

        assertThat(items.keySet()).contains(used, fresh, old).allSatisfy(id -> assertThat(id).isNotNull());
        assertThat(items.values()).noneMatch(item -> item.filename().equals("남의 파일.pdf"));
        assertThat(items.get(used).usedBy()).containsExactly(
                new AttachmentListItem.UsedBy("RESUME", resume.id(), "서랍 이력서"));
        assertThat(items.get(used).deletable()).isFalse();
        assertThat(items.get(fresh).usedBy()).isEmpty();
        assertThat(items.get(fresh).deletable()).isFalse();
        assertThat(items.get(old).deletable()).isTrue();
        assertThat(items.get(old).size()).isEqualTo(5);
    }

    // ── 동시성: 두 트랜잭션 중 하나만 성공한다(6.9 검증 2) ─────────────────────────────

    /** 첫 일을 트랜잭션 안에서 하고 커밋을 미룬 채, 둘째 일이 행 잠금에서 기다리는 것을 본 뒤 커밋한다. */
    private <T> Throwable race(Runnable first, Supplier<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                first.run();
                done.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            Future<T> waiter = pool.submit(second::get);
            long deadline = System.currentTimeMillis() + 2000;
            while (waitingOnLock() == 0 && System.currentTimeMillis() < deadline) Thread.onSpinWait();
            assertThat(waitingOnLock()).as("둘째 트랜잭션이 잠금에서 기다린다").isPositive();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            try {
                waiter.get(10, TimeUnit.SECONDS);
                return null;
            } catch (java.util.concurrent.ExecutionException e) {
                return e.getCause();
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private int waitingOnLock() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
                + "and wait_event_type = 'Lock'", Integer.class);
    }

    @Test
    void referenceWrittenFirstMakesDeleteConflict() throws Exception {
        UUID file = attachment(OWNER_ID, "경합 파일.pdf", 30);
        ResumeResponse resume = resume("경합", "");

        Throwable deleteResult = race(() -> save(resume, certificate(file.toString())), () -> {
            attachments.delete(file);
            return null;
        });

        assertThat(code(deleteResult)).isEqualTo("ATTACHMENT_IN_USE");
        assertThat(exists(file)).isTrue();
        assertThat(resumes.findById(resume.id()).content().certificates().get(0).fileId()).isEqualTo(file.toString());
    }

    @Test
    void deleteFirstMakesNewReferenceFail() throws Exception {
        UUID file = attachment(OWNER_ID, "경합 파일.pdf", 30);
        ResumeResponse resume = resume("경합", "");

        Throwable saveResult = race(() -> attachments.delete(file), () -> save(resume, certificate(file.toString())));

        assertThat(saveResult).isInstanceOfSatisfying(ApiRuleException.class,
                e -> assertThat(e.getCode()).isEqualTo("ATTACHMENT_NOT_FOUND"));
        assertThat(exists(file)).isFalse();
        assertThat(resumes.findById(resume.id()).content().certificates().get(0).fileId()).isNullOrEmpty();
    }

    /** 복제는 원본을 잠그고 읽는다: 원본 저장이 먼저면 복제본에는 빠진 첨부가 없고, 그 파일은 지울 수 있다. */
    @Test
    void sourceSaveBeforeCopyLeavesTheFileFree() throws Exception {
        UUID file = attachment(OWNER_ID, "원본 파일.pdf", 30);
        ResumeResponse source = resume("원본", file.toString());

        Throwable copyResult = race(() -> save(source, certificate("")),
                () -> resumes.copy(source.id(), new ResumeCopyRequest("복제본")));

        assertThat(copyResult).isNull();
        attachments.delete(file);
        assertThat(exists(file)).isFalse();
    }

    /** 복제가 먼저면 복제본이 그 첨부를 들고 있어, 원본에서 뺀 뒤에도 지우면 409 다. */
    @Test
    void copyBeforeSourceSaveKeepsTheFile() throws Exception {
        UUID file = attachment(OWNER_ID, "원본 파일.pdf", 30);
        ResumeResponse source = resume("원본", file.toString());

        Throwable saveResult = race(() -> resumes.copy(source.id(), new ResumeCopyRequest("복제본")),
                () -> save(source, certificate("")));

        assertThat(saveResult).isNull();
        assertThat(code(catchThrowable(() -> attachments.delete(file)))).isEqualTo("ATTACHMENT_IN_USE");
        assertThat(exists(file)).isTrue();
    }

    /** 경험 수정으로 근거를 새로 더하는 쪽이 먼저면 삭제가 기다렸다가 409 다. */
    @Test
    void experienceEvidenceWrittenFirstMakesDeleteConflict() throws Exception {
        UUID file = attachment(OWNER_ID, "경험 경합.pdf", 30);
        ExperienceViews.Saved created = experiences.save(null, experience(null, noEvidence(), null));

        Throwable deleteResult = race(() -> experiences.save(created.id(),
                experience(created.id(), evidenceOn(file), created.version())), () -> {
            attachments.delete(file);
            return null;
        });

        assertThat(code(deleteResult)).isEqualTo("ATTACHMENT_IN_USE");
        assertThat(exists(file)).isTrue();
    }

    /** 삭제가 먼저면 근거를 새로 더하는 경험 수정이 400 이고, 경험은 그대로다. */
    @Test
    void deleteFirstMakesNewEvidenceFail() throws Exception {
        UUID file = attachment(OWNER_ID, "경험 경합.pdf", 30);
        ExperienceViews.Saved created = experiences.save(null, experience(null, noEvidence(), null));

        Throwable saveResult = race(() -> attachments.delete(file),
                () -> experiences.save(created.id(), experience(created.id(), evidenceOn(file), created.version())));

        assertThat(code(saveResult)).isEqualTo("ATTACHMENT_NOT_FOUND");
        assertThat(exists(file)).isFalse();
        assertThat(jdbc.queryForObject("select version from experiences where id = ?", Long.class, created.id()))
                .isEqualTo(created.version());
    }

    private SubmissionRequest receiptOn(UUID resumeId, UUID file) {
        return new SubmissionRequest(SubmissionService.Level.RECEIPT_VERIFIED, List.of(resumeId), List.of(),
                "접수가 완료되었습니다", null, List.of(file));
    }

    private UUID posting(String company) {
        return postings.create(new JobPostingRequest(null, company, company + " 신입 공채", null, EmploymentType.FULL_TIME, null,
                ApplicationStatus.INTERESTED, null, null, List.of(), List.of())).id();
    }

    /** 제출 기록(접수 근거)이 먼저 그 파일을 잡으면 삭제는 기다렸다가 409 다. */
    @Test
    void submissionFirstMakesDeleteConflict() throws Exception {
        UUID file = attachment(OWNER_ID, "접수 확인.pdf", 30);
        UUID postingId = posting("경합사");
        UUID resumeId = resume("제출용", "").id();

        Throwable deleteResult = race(() -> submissions.confirm(postingId, receiptOn(resumeId, file)), () -> {
            attachments.delete(file);
            return null;
        });

        assertThat(code(deleteResult)).isEqualTo("ATTACHMENT_IN_USE");
        assertThat(deleteResult).hasMessageContaining("제출 기록");
        assertThat(exists(file)).isTrue();
    }

    /** 삭제가 먼저면 제출 기록이 lockAttachments 에서 기다렸다가 400 이고, 기록은 남지 않는다. */
    @Test
    void deleteFirstMakesSubmissionFail() throws Exception {
        UUID file = attachment(OWNER_ID, "접수 확인.pdf", 30);
        UUID postingId = posting("경합이사");
        UUID resumeId = resume("제출용", "").id();

        Throwable confirmResult = race(() -> attachments.delete(file), () -> submissions.confirm(postingId, receiptOn(resumeId, file)));

        assertThat(code(confirmResult)).isEqualTo("ATTACHMENT_NOT_FOUND");
        assertThat(exists(file)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from submissions where posting_id = ?", Integer.class, postingId)).isZero();
    }

    /** 삭제는 잠금을 3초까지만 기다린다(set local lock_timeout). 넘으면 연결을 붙잡지 않고 409 BUSY 다. */
    @Test
    void deleteGivesUpOnLockAfterTimeout() throws Exception {
        UUID file = attachment(OWNER_ID, "잡힌 파일.pdf", 30);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForList("select id from attachments where id = ? for key share", UUID.class, file);
                held.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            Throwable thrown = catchThrowable(() -> attachments.delete(file));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(thrown).as("잠금 대기 상한이 없으면 잡은 쪽이 놓을 때까지 기다렸다가 지워 버린다").isNotNull();
            assertThat(code(thrown)).isEqualTo("BUSY");
            assertThat(waitedMs).isBetween(2_500L, 10_000L);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(exists(file)).isTrue();
    }
}
