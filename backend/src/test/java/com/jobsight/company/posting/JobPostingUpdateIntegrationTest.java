package com.jobsight.company.posting;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.position.dto.PositionSummary;
import com.jobsight.company.posting.dto.JobPostingRequest;
import com.jobsight.company.posting.dto.JobPostingResponse;
import com.jobsight.company.posting.dto.PositionNameRequest;
import com.jobsight.company.posting.dto.StepRequest;
import com.jobsight.company.posting.dto.StepResponse;
import com.jobsight.company.resume.ResumeService;
import com.jobsight.company.resume.dto.ResumeRequest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 이름만 보낸 공고 수정이 직무를 지웠다 다시 만들지 않는지 실제 FK 로 본다(설계 1.4 T3).
 * 직무가 새로 만들어지면 resume_positions 가 ON DELETE CASCADE 로 함께 지워진다.
 */
@Testcontainers
@SpringBootTest
class JobPostingUpdateIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean CurrentUser currentUser;
    @Autowired JobPostingService postings;
    @Autowired ResumeService resumes;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void owner() {
        given(currentUser.id()).willReturn(OWNER_ID);
        jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now()) on conflict (id) do nothing",
                OWNER_ID, "posting-it@example.com", "posting-it-sub");
    }

    private static JobPostingRequest request(List<PositionNameRequest> positions, List<StepRequest> steps) {
        return new JobPostingRequest(null, "통합 회사", "2026 신입 공채", null, EmploymentType.FULL_TIME, null,
                ApplicationStatus.INTERESTED, null, null, positions, steps);
    }

    private static UUID idOf(JobPostingResponse posting, String positionName) {
        return posting.positions().stream().filter(p -> p.name().equals(positionName))
                .findFirst().orElseThrow().id();
    }

    @Test
    void nameOnlyUpdateKeepsPositionIdAndResumeLink() {
        JobPostingResponse created = postings.create(request(
                List.of(new PositionNameRequest(null, "백엔드 개발")), List.of(new StepRequest("서류", null, null, null))));
        UUID positionId = created.positions().get(0).id();
        UUID resumeId = resumes.create(new ResumeRequest("지원용", null, List.of(positionId), null)).id();

        // AI 가 상세를 읽지 않고 직무 이름만 보내고, 절차는 빼고 보낸 수정.
        JobPostingResponse updated = postings.update(created.id(),
                request(List.of(new PositionNameRequest(null, "백엔드 개발")), null));

        assertThat(updated.positions()).extracting(PositionSummary::id).containsExactly(positionId);
        assertThat(updated.steps()).extracting(StepResponse::name).containsExactly("서류");
        assertThat(resumes.findById(resumeId).positionIds()).containsExactly(positionId);
        assertThat(jdbc.queryForObject("select count(*) from resume_positions where resume_id = ? and position_id = ?",
                Integer.class, resumeId, positionId)).isEqualTo(1);
    }

    /**
     * 웹 폼처럼 id 를 담아 보낸 수정. id 로 지목한 직무는 앞 항목의 이름 맞춤에 뺏기지 않고 이름만 바뀌며,
     * 그 직무의 이력서 연결도 바뀐 이름에 그대로 붙어 있다. 빠진 직무는 지워지고 지원 직무였다면 비워진다.
     */
    @Test
    void idUpdateRenamesClaimedPositionAndDropsMissingOne() {
        JobPostingResponse created = postings.create(request(List.of(
                new PositionNameRequest(null, "백엔드"), new PositionNameRequest(null, "프론트엔드")), List.of()));
        UUID backendId = idOf(created, "백엔드");
        UUID frontendId = idOf(created, "프론트엔드");
        jdbc.update("update job_postings set target_position_id = ? where id = ?", backendId, created.id());
        UUID resumeId = resumes.create(new ResumeRequest("지원용", null, List.of(frontendId), null)).id();

        JobPostingResponse updated = postings.update(created.id(), request(List.of(
                new PositionNameRequest(null, "프론트엔드"), new PositionNameRequest(frontendId, "웹 프론트엔드")), null));

        assertThat(updated.positions()).filteredOn(p -> p.id().equals(frontendId))
                .extracting(PositionSummary::name).containsExactly("웹 프론트엔드");
        assertThat(updated.positions()).extracting(PositionSummary::name)
                .containsExactlyInAnyOrder("프론트엔드", "웹 프론트엔드");
        assertThat(updated.positions()).extracting(PositionSummary::id).doesNotContain(backendId);
        assertThat(updated.targetPositionId()).isNull();
        assertThat(resumes.findById(resumeId).positionIds()).containsExactly(frontendId);
    }

    /**
     * 같은 새 직무를 담은 수정 두 건이 동시에 와도 그 직무는 한 행이다(불변 조건 10).
     * 공고 행을 잠그지 않으면 둘 다 기존 직무 목록을 읽은 뒤 각자 새 행을 만든다. 경쟁이라 여러 번 돌린다.
     */
    @Test
    void parallelUpdatesDoNotDuplicateNewPosition() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                JobPostingResponse created = postings.create(request(
                        List.of(new PositionNameRequest(null, "백엔드")), List.of()));
                UUID backendId = idOf(created, "백엔드");
                JobPostingRequest addData = request(List.of(
                        new PositionNameRequest(backendId, "백엔드"), new PositionNameRequest(null, "데이터")), null);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<JobPostingResponse>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return postings.update(created.id(), addData);
                    }));
                }
                start.countDown();
                for (Future<JobPostingResponse> result : results) {
                    result.get(15, TimeUnit.SECONDS);
                }

                assertThat(jdbc.queryForObject("select count(*) from positions where posting_id = ?",
                        Integer.class, created.id())).as("round %d", round).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
