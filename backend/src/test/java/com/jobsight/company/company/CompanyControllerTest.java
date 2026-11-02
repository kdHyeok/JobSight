package com.jobsight.company.company;

import com.jobsight.company.common.GlobalExceptionHandler;
import com.jobsight.company.company.dto.CompanyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyControllerTest {
    @Mock
    private CompanyService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new CompanyController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void listReturnsCompanies() throws Exception {
        var company = new CompanyResponse(
                UUID.randomUUID(), "테스트 기업", null,
                List.of("IT서비스", "금융권"),
                List.of(new com.jobsight.company.company.dto.BusinessAreaResponse("클라우드", "매출의 절반")),
                CompanySize.MEDIUM,
                1_000_000_000L, RevenueUnit.HUNDRED_MILLION, 250, "서울 강남", LocalDate.of(2015, 3, 1),
                null, null, null, List.of(), Instant.now(), Instant.now(), null
        );
        given(service.findAll()).willReturn(List.of(company));

        mockMvc.perform(get("/api/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("테스트 기업"))
                .andExpect(jsonPath("$[0].industries[0]").value("IT서비스"))
                .andExpect(jsonPath("$[0].companySize").value("MEDIUM"));
    }

    @Test
    void invalidCreateReturnsFieldErrors() throws Exception {
        mockMvc.perform(post("/api/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    @Test
    void invalidEnumReturnsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"테스트 기업\",\"companySize\":\"NOT_A_SIZE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedJsonReturnsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"테스트 기업\",\"summary\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
