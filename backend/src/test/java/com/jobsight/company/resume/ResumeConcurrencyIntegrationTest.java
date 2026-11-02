package com.jobsight.company.resume;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.resume.dto.ResumeResponse;
import com.jobsight.company.resume.dto.ResumeRowRequest;
import com.jobsight.company.resume.dto.ResumeRowResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 실제 PostgreSQL 행 잠금과 version 으로 이력서 동시 수정을 막는지 본다 — docs/resumes.md 불변 조건 8·10.
 * 트랜잭션이 커밋돼야 경쟁이 생기므로 클래스에 @Transactional 을 걸지 않는다.
 */
@Testcontainers
@SpringBootTest
class ResumeConcurrencyIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean CurrentUser currentUser;
    @Autowired ResumeService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void owner() {
        given(currentUser.id()).willReturn(OWNER_ID);
        jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now()) on conflict (id) do nothing",
                OWNER_ID, "resume-it@example.com", "resume-it-sub");
    }

    private ResumeRowRequest project(String name) {
        return mapper.convertValue(Map.of("name", name), ResumeRowRequest.class);
    }

    /** 잠금 없이 읽고 쓰면 동시에 들어온 행 추가 둘 중 하나가 사라진다(설계 1.4 T1). */
    @Test
    void parallelRowAddsKeepBothRows() throws Exception {
        UUID id = service.create(new ResumeRequest("동시 추가", null, List.of(), null)).id();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ResumeRowResult>> results = new ArrayList<>();
        for (String name : List.of("A", "B")) {
            results.add(pool.submit(() -> {
                start.await();
                return service.addRow(id, ResumeSection.projects, project(name), null);
            }));
        }
        start.countDown();
        for (Future<ResumeRowResult> result : results) {
            result.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();

        ResumeResponse saved = service.findById(id);
        assertThat(saved.content().projects()).extracting(ResumeContent.Project::name).containsExactlyInAnyOrder("A", "B");
        assertThat(saved.version()).isEqualTo(2);
    }

    /** AI 가 읽은 뒤 웹에서 0번을 지우면, AI 의 0번 수정은 엉뚱한 행에 들어가지 않고 409 다(설계 1.4 T2). */
    @Test
    void staleIndexEditIsRejected() {
        UUID id = service.create(new ResumeRequest("행 번호", null, List.of(), null)).id();
        service.addRow(id, ResumeSection.projects, project("Planetory"), null);
        long readByAi = service.addRow(id, ResumeSection.projects, project("JOBIS"), null).version();

        ResumeResponse web = service.findById(id);
        ResumeContent withoutFirst = new ResumeContent(null, null, null, null, null, null, null, null,
                List.of(web.content().projects().get(1)));
        service.update(id, new ResumeRequest(web.name(), withoutFirst, null, web.version()));

        assertThatThrownBy(() -> service.updateRow(id, ResumeSection.projects, 0, project("Planetory 수정"), readByAi))
                .isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo("RESUME_STALE"));
        assertThat(service.findById(id).content().projects()).extracting(ResumeContent.Project::name).containsExactly("JOBIS");
    }
}
