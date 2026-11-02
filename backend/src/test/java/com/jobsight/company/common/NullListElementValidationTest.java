package com.jobsight.company.common;

import com.jobsight.company.experience.ExperienceKind;
import com.jobsight.company.experience.dto.ExperienceFacts;
import com.jobsight.company.experience.dto.ExperienceRequests;
import com.jobsight.company.experience.dto.ExperienceSearchRequest;
import com.jobsight.company.resume.dto.ResumeAgentUpdateRequest;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionCreateRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionUpdateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 목록 요소의 null 은 검증(400)에서 막는다. @Size·@Valid 는 null 요소를 통과시켜, 서비스의 List.copyOf·Set.copyOf·
 * 필드 접근에서 NPE(500)가 났다(OCR 리뷰). REST 와 MCP(McpTools 가 같은 Validator 를 쓴다) 둘 다 이 제약으로 막힌다.
 */
class NullListElementValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @SafeVarargs
    private static <T> List<T> withNull(T... values) {
        List<T> list = new java.util.ArrayList<>(Arrays.asList(values));
        list.add(null);
        return list;
    }

    private void assertRejected(Object request) {
        assertThat(validator.validate(request)).as(request.toString()).isNotEmpty();
    }

    @Test
    void searchRequirementsRejectNullElements() {
        assertRejected(new ExperienceSearchRequest(UUID.randomUUID(), null, withNull(), null));
    }

    @Test
    void factListsRejectNullElements() {
        ExperienceFacts.Item item = new ExperienceFacts.Item(null, "행동", withNull());
        assertRejected(new ExperienceFacts(null, null, null, withNull(), null, null, null, null));
        assertRejected(new ExperienceFacts(null, null, null, null, withNull(), null, null, null));
        assertRejected(new ExperienceFacts(null, null, null, null, null, withNull(), null, null));
        assertRejected(new ExperienceFacts(null, null, null, null, null, null, withNull(), null));
        assertRejected(new ExperienceFacts(null, null, null, null, null, null, null, withNull()));
        assertRejected(new ExperienceFacts(null, null, null, List.of(item), null, null, null, null));
        assertRejected(new ExperienceRequests.Save(ExperienceKind.PROJECT, "제목", null, null, null, null, withNull(), null, null));
    }

    @Test
    void selfIntroIdListsRejectNullElements() {
        assertRejected(new SelfIntroductionCreateRequest(null, withNull(), null, "질문", "답변"));
        assertRejected(new SelfIntroductionCreateRequest(List.of(UUID.randomUUID()), null, withNull(), "질문", "답변"));
        assertRejected(new SelfIntroductionCreateRequest(withNull(UUID.randomUUID()), null, null, "질문", "답변"));
        assertRejected(new SelfIntroductionUpdateRequest(withNull(), null, null, "질문", "답변", 0L));
        assertRejected(new SelfIntroductionUpdateRequest(List.of(), withNull(), null, "질문", "답변", 0L));
        assertRejected(new SelfIntroductionUpdateRequest(List.of(), null, withNull(), "질문", "답변", 0L));
    }

    @Test
    void resumeIdListsRejectNullElements() {
        assertRejected(new ResumeRequest("이력서", null, null, 0L, withNull()));
        assertRejected(new ResumeRequest("이력서", null, withNull(), 0L, null));
        assertRejected(new ResumeAgentUpdateRequest("이력서", null, null, 0L, withNull()));
        assertRejected(new ResumeAgentUpdateRequest("이력서", null, withNull(), 0L, null));
    }
}
