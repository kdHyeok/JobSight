package com.jobsight.company.selfintro;

import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.GlobalExceptionHandler;
import com.jobsight.company.common.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹 전용 확정 POST /api/self-introductions/{id}/confirm 의 오류 매핑. 인증·CSRF 는 보안 필터가 맡고
 * scripts/smoke.sh 가 실제 스택에서 "CSRF 없음 → 403" 을 본다.
 */
@ExtendWith(MockitoExtension.class)
class SelfIntroductionControllerTest {
    private static final String CONFIRM = ApiPaths.SELF_INTRODUCTIONS + "/{id}/confirm";

    @Mock SelfIntroductionService service;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SelfIntroductionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    /** 연결되지 않은 대상은 404 LINK_NOT_FOUND, 남의 문항은 404, 상한을 넘는 요청은 서비스에 닿기 전에 400 이다. */
    @Test
    void confirmMapsNotFoundAndValidationErrors() throws Exception {
        UUID unlinked = UUID.randomUUID(), foreign = UUID.randomUUID();
        given(service.confirm(eq(unlinked), any())).willThrow(new ApiRuleException(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND",
                "연결되지 않은 직무나 문항 유형은 확정할 수 없습니다."));
        given(service.confirm(eq(foreign), any())).willThrow(new ResourceNotFoundException(foreign));

        mockMvc.perform(post(CONFIRM, unlinked).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
        mockMvc.perform(post(CONFIRM, foreign).contentType(MediaType.APPLICATION_JSON).content("{\"questionTypes\":[\"MOTIVATION\"]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(CONFIRM, unlinked).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionTypes\":[\"MOTIVATION\",\"ASPIRATION\",\"JOB_FIT\",\"GROWTH\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(service, times(2)).confirm(any(), any());
    }
}
