package com.jobsight.company.resume;

import com.jobsight.company.attachment.dto.AttachmentResponse;
import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.resume.dto.ResumeResponse;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 이력서 상세에 싣는 첨부 메타가 실제 쿼리에서도 내 것으로만 한정되는지 본다 — docs/attachments.md 불변 조건 1.
 * 남의 첨부 id 를 문서에 적어 넣어도 그 파일의 이름·크기가 응답에 나가면 안 된다.
 */
@Testcontainers
@SpringBootTest
class ResumeAttachmentsIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

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
    void users() {
        given(currentUser.id()).willReturn(OWNER_ID);
        for (UUID id : List.of(OWNER_ID, OTHER_ID)) {
            jdbc.update("insert into app_users (id, email, role, status, google_sub, created_at, updated_at) "
                    + "values (?, ?, 'USER', 'ACTIVE', ?, now(), now()) on conflict (id) do nothing",
                    id, id + "@example.com", "sub-" + id);
        }
    }

    private UUID attachment(UUID owner, String filename) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into attachments (id, owner_id, filename, content_type, size_bytes, data, created_at) "
                + "values (?, ?, ?, 'application/pdf', 5, ?, now())",
                id, owner, filename, "%PDF-".getBytes(StandardCharsets.US_ASCII));
        return id;
    }

    @Test
    void detailCarriesOnlyMyAttachmentMetas() {
        UUID mine = attachment(OWNER_ID, "졸업증.pdf");
        UUID theirs = attachment(OTHER_ID, "남의 성적표.pdf");
        ResumeContent content = mapper.convertValue(Map.of("educations", List.of(Map.of(
                "school", "대학", "diplomaId", mine.toString()))), ResumeContent.class);
        UUID id = service.create(new ResumeRequest("첨부", content, List.of(), null)).id();
        // 남의 id 는 새로 적으면 규칙 B 가 400 으로 막는다(AttachmentDeleteIntegrationTest). 예전에 저장된 문서에 있을 수 있어 직접 넣는다.
        jdbc.update("update resumes set content = jsonb_set(content, '{educations,0,transcriptId}', to_jsonb(?::text)) where id = ?",
                theirs.toString(), id);

        ResumeResponse detail = service.findById(id);

        assertThat(detail.attachments()).containsExactly(
                Map.entry(mine, new AttachmentResponse(mine, "졸업증.pdf", "application/pdf", 5)));
    }
}
