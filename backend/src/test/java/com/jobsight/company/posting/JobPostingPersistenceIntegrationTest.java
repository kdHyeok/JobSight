package com.jobsight.company.posting;

import com.jobsight.company.position.Position;
import com.jobsight.company.position.PositionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Transactional
class JobPostingPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ad");
    private static final UUID COMPANY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JobPostingRepository repository;
    @Autowired PositionRepository positions;
    @Autowired EntityManager entityManager;

    @Test
    void replacingExistingStepsDoesNotCollideOnSequence() {
        JobPosting posting = repository.saveAndFlush(postingWithSteps("서류", "면접"));

        posting.update(attributesWithSteps("서류 검토", "필기", "최종 면접"));
        repository.saveAndFlush(posting);

        assertThat(posting.getSteps()).extracting(RecruitmentStep::getName)
                .containsExactly("서류 검토", "필기", "최종 면접");
    }

    @Test
    void expiredInterestedPostingIsSelectedForAutoArchive() {
        JobPosting posting = repository.saveAndFlush(new JobPosting(OWNER_ID,
                new JobPostingAttributes(COMPANY_ID, "지난 공고", null, EmploymentType.FULL_TIME,
                        Instant.now().minus(1, ChronoUnit.DAYS), ApplicationStatus.INTERESTED, null, null, List.of())));

        assertThat(repository.findPendingAutoArchive(OWNER_ID, Instant.now()))
                .extracting(JobPosting::getId).contains(posting.getId());
    }

    @Test
    void deletingPostingCascadesPositions() {
        JobPosting posting = repository.saveAndFlush(postingWithSteps("서류"));
        Position position = positions.saveAndFlush(new Position(OWNER_ID, posting.getId(), "백엔드 개발"));

        repository.delete(posting);
        repository.flush();
        entityManager.clear();

        assertThat(positions.findById(position.getId())).isEmpty();
    }

    @Test
    void positionDeleteLockTimeoutIsScopedToCurrentTransaction() {
        repository.setLocalLockTimeout();

        assertThat(entityManager.createNativeQuery("show lock_timeout", String.class).getSingleResult())
                .isEqualTo("3s");
    }

    private static JobPosting postingWithSteps(String... names) {
        return new JobPosting(OWNER_ID, attributesWithSteps(names));
    }

    private static JobPostingAttributes attributesWithSteps(String... names) {
        return new JobPostingAttributes(COMPANY_ID, "공고", null, EmploymentType.FULL_TIME, null,
                ApplicationStatus.INTERESTED, null, null,
                List.of(names).stream().map(name -> new RecruitmentStep(name, null, null, null)).toList());
    }
}
